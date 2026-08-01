/*
	libcamera: An implementation of the library required by Android OS 3.2 so
	it can access V4L2 devices as cameras.

    (C) 2011 Eduardo José Tagle <ejtagle@tutopia.com>
	(C) 2011 RedScorpion

	Based on several packages:
		- luvcview: Sdl video Usb Video Class grabber
			(C) 2005,2006,2007 Laurent Pinchart && Michel Xhaard

		- spcaview
			(C) 2003,2004,2005,2006 Michel Xhaard

		- JPEG decoder from http://www.bootsplash.org/
			(C) August 2001 by Michael Schroeder, <mls@suse.de>

		- libcamera V4L for Android 2.2
			(C) 2009 0xlab.org - http://0xlab.org/
			(C) 2010 SpectraCore Technologies
				Author: Venkat Raju <codredruids@spectracoretech.com>
				Based on a code from http://code.google.com/p/android-m912/downloads/detail?name=v4l2_camera_v2.patch

		- guvcview:  http://guvcview.berlios.de
			Paulo Assis <pj.assis@gmail.com>
			Nobuhiro Iwamatsu <iwamatsu@nigauri.org>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <utils/threads.h>
extern "C" {
#include <jpeglib.h>
#include <transupp.h>
}
#include "Converter.h"
#include "V4L2Camera.h"

/*clip value between 0 and 255*/
#define CLIP(value) (uint8_t)(((value)>0xFF)?0xff:(((value)<0)?0:(value)))

static uint8_t* gBuf = NULL;
static uint8_t* gUbuf = NULL;
static uint8_t* gVbuf = NULL;
static uint8_t* gUbuf_temp = NULL;
static uint8_t* gVbuf_temp = NULL;
static uint8_t *gYbuf_temp = NULL;
static uint8_t *gUVbuf_temp = NULL;

android::Mutex memLock;

/* convert yuyv to YVU420SP */
void yuyv_to_yvu420sp(uint8_t *dst,int dstStride, int dstHeight, uint8_t *src, int srcStride, int width, int height)
{
	// Start of Y plane
	uint8_t* dstY = dst;

	// Calculate start of UV plane
	uint8_t* dstVU = dst + dstStride * dstHeight;

	int h=0;
	int w=0;
	int dyvu = dstStride - width;
	int sw   = srcStride - (width<<1);
	for (h = 0; h<height; h +=2) {
		for (w=0; w < width; w += 2) {
			*dstY++  = *src++;							// Y0
			dstVU[1] = (src[0] + src[srcStride]) >> 1;	// U
			src++;
			*dstY++  = *src++;							// Y1
			dstVU[0] = (src[0] + src[srcStride]) >> 1;	// V
			src++;
			dstVU+=2;
		}
		src   += sw;
		dstY  += dyvu;
		dstVU += dyvu;
		for (w=0; w < width; w += 2) {
			*dstY++  = *src;	// Y0
			src += 2;
			*dstY++  = *src;	// Y1
			src += 2;
		}
		src   += sw;
		dstY  += dyvu;
	}
}

/* convert yuyv to YVU420P */
/* This format assumes that the horizontal strides (luma and chroma) are multiple of 16 pixels */
void yuyv_to_yvu420p(uint8_t *dst,int dstStride, int dstHeight, uint8_t *src, int srcStride, int width, int height)
{
	// Calculate the chroma plane stride
	int dstVUStride = ((dstStride >> 1) + 15) & (-16);

	// Start of Y plane
	uint8_t* dstY = dst;

	// Calculate start of V plane
	uint8_t* dstV = dst + dstStride * dstHeight;

	// Calculate start of U plane
	uint8_t* dstU = dstV + (dstVUStride * dstHeight >> 1);

	int h=0;
	int w=0;
	int dy  = dstStride - width;
	int dvu = dstVUStride - (width >> 1);
	int sw  = srcStride - (width<<1);
	for (h = 0; h<height; h +=2) {
		for (w=0; w < width; w += 2) {
			*dstY++ = *src++;							// Y0
			*dstU++ = (src[0] + src[srcStride]) >> 1;	// U
			src++;
			*dstY++ = *src++;							// Y1
			*dstV++ = (src[0] + src[srcStride]) >> 1;	// V
			src++;
		}
		src   += sw;
		dstY  += dy;
		dstU += dvu;
		dstV += dvu;
		for (w=0; w < width; w += 2) {
			*dstY++  = *src;	// Y0
			src += 2;
			*dstY++  = *src;	// Y1
			src += 2;
		}
		src   += sw;
		dstY  += dy;
	}
}

/* This format assumes that the horizontal strides (luma and chroma) are multiple of 16 pixels */
void yuyv_to_yuv420p(uint8_t *dst,int dstStride, int dstHeight, uint8_t *src, int srcStride, int width, int height)
{
	// Calculate the chroma plane stride
	int dstUVStride = ((dstStride >> 1) + 15) & (-16);

	// Start of Y plane
	uint8_t* dstY = dst;

	// Calculate start of U plane
	uint8_t* dstU = dst + dstStride * dstHeight;

	// Calculate start of V plane
	uint8_t* dstV = dstU + (dstUVStride * dstHeight >> 1);

	int h=0;
	int w=0;
	int dy  = dstStride - width;
	int dvu = dstUVStride - (width >> 1);
	int sw  = srcStride - (width<<1);
	for (h = 0; h<height; h +=2) {
		for (w=0; w < width; w += 2) {
			*dstY++ = *src++;							// Y0
			*dstU++ = (src[0] + src[srcStride]) >> 1;	// U
			src++;
			*dstY++ = *src++;							// Y1
			*dstV++ = (src[0] + src[srcStride]) >> 1;	// V
			src++;
		}
		src   += sw;
		dstY  += dy;
		dstU += dvu;
		dstV += dvu;
		for (w=0; w < width; w += 2) {
			*dstY++  = *src;	// Y0
			src += 2;
			*dstY++  = *src;	// Y1
			src += 2;
		}
		src   += sw;
		dstY  += dy;
	}
}


/* convert yuyv to YVU422P */
/* This format assumes that the horizontal strides (luma and chroma) are multiple of 16 pixels */
void yuyv_to_yvu422p(uint8_t *dst,int dstStride, int dstHeight, uint8_t *src, int srcStride, int width, int height)
{
	// Calculate the chroma plane stride
	int dstVUStride = ((dstStride >> 1) + 15) & (-16);

	// Start of Y plane
	uint8_t* dstY = dst;

	// Calculate start of U plane
	uint8_t* dstV = dst + dstStride * dstHeight;

	// Calculate start of V plane
	uint8_t* dstU = dstV + (dstVUStride * dstHeight);

	int h=0;
	int w=0;
	int dy  = dstStride - width;
	int dvu = dstVUStride - (width >> 1);
	int sw  = srcStride - (width<<1);
	for (h = 0; h<height; h ++) {
		for (w=0; w < width; w += 2) {
			*dstY++ = *src++;	// Y0
			*dstU++ = *src++;	// U
			*dstY++ = *src++;	// Y1
			*dstV++ = *src++;	// V
		}
		src  += sw;
		dstY += dy;
		dstU += dvu;
		dstV += dvu;
	}
}


/*convert y16 (grey) to yuyv (packed)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing y16 (grey) data frame
*      width: picture width
*      height: picture height
*/
void y16_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int srcStride, int width, int height)
{
	uint16_t *ptmp= (uint16_t *) src;

	int h=0;
	int w=0;
	int dw = dstStride - (width << 1);
	int sw = (srcStride>>1) - width;

	for(h=0;h<height;h++)
	{
		for(w=0;w<width;w+=2)
		{
			/* Y0 */
			*dst++ = (uint8_t) (ptmp[0] & 0xFF00) >> 8;
			/* U */
			*dst++ = 0x7F;
			/* Y1 */
			*dst++ = (uint8_t) (ptmp[1] & 0xFF00) >> 8;
			/* V */
			*dst++ = 0x7F;

			ptmp += 2;
		}
		dst  += dw; // Correct for stride
		ptmp += sw;
	}
}

/*convert yyuv (packed) to yuyv (packed)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yyuv packed data frame
*      width: picture width
*      height: picture height
*/
void yyuv_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int srcStride, int width, int height)
{
	uint8_t *ptmp=NULL;
	uint8_t *pfmb=NULL;
	ptmp = src;
	pfmb = dst;

	int h=0;
	int w=0;
	int dw = dstStride - (width << 1);
	int sw = srcStride - (width << 1);

	for(h=0;h<height;h++)
	{
		for(w=0;w<width;w+=2)
		{
			/* Y0 */
			*pfmb++ = ptmp[0];
			/* U */
			*pfmb++ = ptmp[2];
			/* Y1 */
			*pfmb++ = ptmp[1];
			/* V */
			*pfmb++ = ptmp[3];

			ptmp += 4;
		}
		pfmb += dw; // Correct for stride
		ptmp += sw;
	}
}


/*convert uyvy (packed) to yuyv (packed)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing uyvy packed data frame
*      width: picture width
*      height: picture height
*/
void uyvy_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int srcStride, int width, int height)
{
	uint8_t *ptmp = src;
	uint8_t *pfmb = dst;
	int h=0;
	int w=0;
	int dw = dstStride - (width << 1);
	int sw = srcStride - (width << 1);

	for(h=0;h<height;h++)
	{
		for(w=0;w<width;w+=2)
		{
			/* Y0 */
			*pfmb++ = ptmp[1];
			/* U */
			*pfmb++ = ptmp[0];
			/* Y1 */
			*pfmb++ = ptmp[3];
			/* V */
			*pfmb++ = ptmp[2];

			ptmp += 4;
		}
		pfmb += dw; // Correct for stride
		ptmp += sw;
	}
}


/*convert yvyu (packed) to yuyv (packed)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yvyu packed data frame
*      width: picture width
*      height: picture height
*/
void yvyu_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int srcStride, int width, int height)
{
	uint8_t *ptmp=NULL;
	uint8_t *pfmb=NULL;
	ptmp = src;
	pfmb = dst;

	int h=0;
	int w=0;
	int dw = dstStride - (width << 1);
	int sw = srcStride - (width << 1);

	for(h=0;h<height;h++)
	{
		for(w=0;w<width;w+=2)
		{
			/* Y0 */
			*pfmb++ = ptmp[0];
			/* U */
			*pfmb++ = ptmp[3];
			/* Y1 */
			*pfmb++ = ptmp[2];
			/* V */
			*pfmb++ = ptmp[1];

			ptmp += 4;
		}
		pfmb += dw; // Correct for stride
		ptmp += sw;
	}
}

/*convert yuv 420 planar (yu12) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv420 planar data frame
*      width: picture width
*      height: picture height
*/
void yuv420_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *pu;
	uint8_t *pv;

	int linesize = width * 2;
	int uvlinesize = width / 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	pu=py+(width*height);
	pv=pu+(width*height/4);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	offsety = 0;
	offsetuv = 0;

	for(h=0;h<height;h+=2)
	{
		wy=0;
		wuv=0;

		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++        = py[wy + offsety];
			/*y10*/
			dst[dstStride-1] = py[wy + offsety + width];

			/*u0*/
			uint8_t u0 = pu[wuv + offsetuv];
			*dst++        = u0;
			/*u0*/
			dst[dstStride-1] = u0;

			/*y01*/
			*dst++        = py[(wy + 1) + offsety];
			/*y11*/
			dst[dstStride-1] = py[(wy + 1) + offsety + width];

			/*v0*/
			uint8_t u1 = pv[wuv + offsetuv];
			*dst++        = u1;
			/*v0*/
			dst[dstStride-1] = u1;

			wuv++;
			wy+=2;
		}

		dst += dstStride + dw;
		offsety  += width * 2;
		offsetuv += uvlinesize;
	}
}

/*convert yvu 420 planar (yv12) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv420 planar data frame
*      width: picture width
*      height: picture height
*/
void yvu420_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *pv;
	uint8_t *pu;

	int linesize = width * 2;
	int uvlinesize = width / 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	pv=py+(width*height);
	pu=pv+((width*height)/4);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	offsety = 0;
	offsetuv = 0;

	for(h=0;h<height;h+=2)
	{
		wy=0;
		wuv=0;

		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++ = py[wy + offsety];
			/*y10*/
			dst[dstStride-1] = py[wy + offsety + width];

			/*u0*/
			uint8_t u0 = pu[wuv + offsetuv];
			*dst++ = u0;
			/*u0*/
			dst[dstStride-1] = u0;

			/*y01*/
			*dst++ = py[(wy + 1) + offsety];
			/*y11*/
			dst[dstStride-1] = py[(wy + 1) + offsety +  width];

			/*v0*/
			uint8_t v0 = pv[wuv + offsetuv];
			*dst++ = v0;
			/*v0*/
			dst[dstStride-1] = v0;

			wuv++;
			wy+=2;
		}
		dst += dstStride + dw;
		offsety += width * 2;
		offsetuv += uvlinesize;

	}
}

/*convert yuv 420 planar (uv interleaved) (nv12) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv420 (nv12) planar data frame
*      width: picture width
*      height: picture height
*/
void nv12_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *puv;

	int linesize = width * 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	puv=py+(width*height);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	for(h=0;h<height;h+=2)
	{
		wy=0;
		wuv=0;

		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++ = py[wy + offsety];
			/*y10*/
			dst[dstStride-1] = py[wy + offsety + width];

			/*u0*/
			uint8_t u0 = puv[wuv + offsetuv];
			*dst++ = u0;
			/*u0*/
			dst[dstStride-1] = u0;

			/*y01*/
			*dst++ = py[(wy + 1) + offsety];
			/*y11*/
			dst[dstStride-1] = py[(wy + 1) + offsety + width];

			/*v0*/
			uint8_t v0 = puv[(wuv + 1) + offsetuv];
			*dst++ = v0;
			/*v0*/
			dst[dstStride-1] = v0;

			wuv+=2;
			wy+=2;
		}

		dst += dstStride + dw;
		offsety += width * 2;
		offsetuv +=  width;
	}
}

/*convert yuv 420 planar (vu interleaved) (nv21) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv420 (nv21) planar data frame
*      width: picture width
*      height: picture height
*/
void nv21_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *puv;

	int linesize = width * 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	puv=py+(width*height);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	for(h=0;h<height;h+=2)
	{
		wy=0;
		wuv=0;

		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++ = py[wy + offsety];
			/*y10*/
			dst[dstStride-1] = py[wy + offsety + width];

			/*u0*/
			uint8_t u0 = puv[(wuv + 1) + offsetuv];
			*dst++ = u0;
			/*u0*/
			dst[dstStride-1] = u0;

			/*y01*/
			*dst++ = py[(wy + 1) + offsety];

			/*y11*/
			dst[dstStride-1] = py[(wy + 1) + offsety + width];

			/*v0*/
			uint8_t v0 = puv[wuv + offsetuv];
			*dst++ = v0;
			/*v0*/
			dst[dstStride-1] = v0;

			wuv+=2;
			wy+=2;
		}

		dst += dstStride + dw;
		offsety += width * 2;
		offsetuv += width;

	}
}

/*convert yuv 422 planar (uv interleaved) (nv16) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv422 (nv16) planar data frame
*      width: picture width
*      height: picture height
*/
void nv16_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *puv;

	int linesize = width * 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	puv=py+(width*height);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	for(h=0;h<height;h++)
	{
		wy=0;
		wuv=0;

		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++ = py[wy + offsety];
			/*u0*/
			*dst++ = puv[wuv + offsetuv];
			/*y01*/
			*dst++ = py[(wy + 1) + offsety];
			/*v0*/
			*dst++ = puv[(wuv + 1) + offsetuv];

			wuv+=2;
			wy+=2;
		}
		dst += dw;
		offsety += width;
		offsetuv += width;
	}
}

/*convert yuv 422 planar (vu interleaved) (nv61) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing yuv422 (nv61) planar data frame
*      width: picture width
*      height: picture height
*/
void nv61_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *py;
	uint8_t *puv;

	int linesize = width * 2;
	int offsety=0;
	int offsetuv=0;
	int dw = dstStride - (width << 1);

	py=src;
	puv=py+(width*height);

	int h=0;
	int w=0;

	int wy=0;
	int wuv=0;

	for(h=0;h<height;h++)
	{
		wy=0;
		wuv=0;
		for(w=0;w<linesize;w+=4)
		{
			/*y00*/
			*dst++ = py[wy + offsety];
			/*u0*/
			*dst++ = puv[(wuv + 1) + offsetuv];
			/*y01*/
			*dst++ = py[(wy + 1) + offsety];
			/*v0*/
			*dst++ = puv[wuv + offsetuv];

			wuv+=2;
			wy+=2;
		}
		dst += dw;
		offsety += width;
		offsetuv += width;

	}
}

/*convert yuv 411 packed (y41p) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing y41p data frame
*      width: picture width
*      height: picture height
*/
void y41p_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	int h=0;
	int w=0;
	int linesize = width * 3 /2;
	int offset = 0;
	int dw = dstStride - (width << 1);

	for(h=0;h<height;h++)
	{
		offset = linesize * h;
		for(w=0;w<linesize;w+=12)
		{
			*dst++=src[w+1 + offset]; //Y0
			*dst++=src[w   + offset]; //U0
			*dst++=src[w+3 + offset]; //Y1
			*dst++=src[w+2 + offset]; //V0
			*dst++=src[w+5 + offset]; //Y2
			*dst++=src[w   + offset]; //U0
			*dst++=src[w+7 + offset]; //Y3
			*dst++=src[w+2 + offset]; //V0
			*dst++=src[w+8 + offset]; //Y4
			*dst++=src[w+4 + offset]; //U4
			*dst++=src[w+9 + offset]; //Y5
			*dst++=src[w+6 + offset]; //V4
			*dst++=src[w+10+ offset]; //Y6
			*dst++=src[w+4 + offset]; //U4
			*dst++=src[w+11+ offset]; //Y7
			*dst++=src[w+6 + offset]; //V4
		}
		dst += dw;
	}
}

/*convert yuv mono (grey) to yuv 422
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing grey (y only) data frame
*      width: picture width
*      height: picture height
*/
void grey_to_yuyv (uint8_t *dst,int dstStride, uint8_t *src, int srcStride, int width, int height)
{
	int h=0;
	int w=0;
	int dw = dstStride - (width << 1);
	int sw = srcStride - width;

	for(h=0;h<height;h++)
	{
		for(w=0;w<width;w++)
		{
			*dst++=*src++; //Y
			*dst++=0x80;   //U or V
		}
		dst += dw;
		src += sw;
	}
}

/*convert SPCA501 (s501) to yuv 422
* s501  |Y0..width..Y0|U..width/2..U|Y1..width..Y1|V..width/2..V|
* signed values (-128;+127) must be converted to unsigned (0; 255)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing s501 data frame
*      width: picture width
*      height: picture height
*/
void s501_to_yuyv(uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *U, *V, *Y0, *Y1;
	uint8_t *line2;
	int h, w;
	int dw = dstStride - (width << 1);

	Y0 = src; /*fisrt line*/
	for (h = 0; h < height/2; h++ )
	{
		line2 = dst + dstStride;   /* next line          */
		U = Y0 + width;
		Y1 = U + width / 2;
		V = Y1 + width;
		for (w = width / 2; --w >= 0; )
		{
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *U;
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *V;

			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *U++;
			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *V++;
		}
		Y0 += width * 2;                  /* next block of lines */
		dst = line2 + dw;
	}
}

/*convert SPCA505 (s505) to yuv 422
* s505  |Y0..width..Y0|Y1..width..Y1|U..width/2..U|V..width/2..V|
* signed values (-128;+127) must be converted to unsigned (0; 255)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing s501 data frame
*      width: picture width
*      height: picture height
*/
void s505_to_yuyv(uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *U, *V, *Y0, *Y1;
	uint8_t *line2;
	int h, w;
	int dw = dstStride - (width << 1);

	Y0 = src; /*fisrt line*/
	for (h = 0; h < height/2; h++ )
	{
		line2 = dst + dstStride;   /* next line          */
		Y1 = Y0 + width;
		U  = Y1 + width;
		V  = U + width/2;
		for (w = width / 2; --w >= 0; )
		{
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *U;
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *V;

			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *U++;
			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *V++;
		}
		Y0 += width * 2;                  /* next block of lines */
		dst = line2 + dw;
	}
}

/*convert SPCA508 (s508) to yuv 422
* s508  |Y0..width..Y0|U..width/2..U|V..width/2..V|Y1..width..Y1|
* signed values (-128;+127) must be converted to unsigned (0; 255)
* args:
*      dst: pointer to frame buffer (yuyv)
*      dstStride: stride of framebuffer
*      src: pointer to temp buffer containing s501 data frame
*      width: picture width
*      height: picture height
*/
void s508_to_yuyv(uint8_t *dst,int dstStride, uint8_t *src, int width, int height)
{
	uint8_t *U, *V, *Y0, *Y1;
	uint8_t *line2;
	int h, w;
	int dw = dstStride - (width << 1);

	Y0 = src; /*fisrt line*/
	for (h = 0; h < height/2; h++ )
	{
		line2 = dst + dstStride;   /* next line          */
		U = Y0 + width;
		V = U + width/2;
		Y1= V + width/2;
		for (w = width / 2; --w >= 0; )
		{
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *U;
			*dst++ = 0x80 + *Y0++;
			*dst++ = 0x80 + *V;

			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *U++;
			*line2++ = 0x80 + *Y1++;
			*line2++ = 0x80 + *V++;
		}
		Y0 += width * 2;                  /* next block of lines */
		dst = line2 + dw;
	}
}

// raw bayer functions
// from libv4l bayer.c, (C) 2008 Hans de Goede <j.w.r.degoede@hhs.nl>
//Note: original bayer_to_bgr24 code from :
//  1394-Based Digital Camera Control Library
//
//  Bayer pattern decoding functions
//
//  Written by Damien Douxchamps and Frederic Devernay
static void convert_border_bayer_line_to_bgr24( uint8_t* bayer, uint8_t* adjacent_bayer,
	uint8_t *bgr, int width, bool start_with_green, bool blue_line)
{
	int t0, t1;

	if (start_with_green)
	{
	/* First pixel */
		if (blue_line)
		{
			*bgr++ = bayer[1];
			*bgr++ = bayer[0];
			*bgr++ = adjacent_bayer[0];
		}
		else
		{
			*bgr++ = adjacent_bayer[0];
			*bgr++ = bayer[0];
			*bgr++ = bayer[1];
		}
		/* Second pixel */
		t0 = (bayer[0] + bayer[2] + adjacent_bayer[1] + 1) / 3;
		t1 = (adjacent_bayer[0] + adjacent_bayer[2] + 1) >> 1;
		if (blue_line)
		{
			*bgr++ = bayer[1];
			*bgr++ = t0;
			*bgr++ = t1;
		}
		else
		{
			*bgr++ = t1;
			*bgr++ = t0;
			*bgr++ = bayer[1];
		}
		bayer++;
		adjacent_bayer++;
		width -= 2;
	}
	else
	{
		/* First pixel */
		t0 = (bayer[1] + adjacent_bayer[0] + 1) >> 1;
		if (blue_line)
		{
			*bgr++ = bayer[0];
			*bgr++ = t0;
			*bgr++ = adjacent_bayer[1];
		}
		else
		{
			*bgr++ = adjacent_bayer[1];
			*bgr++ = t0;
			*bgr++ = bayer[0];
		}
		width--;
	}

	if (blue_line)
	{
		for ( ; width > 2; width -= 2)
		{
			t0 = (bayer[0] + bayer[2] + 1) >> 1;
			*bgr++ = t0;
			*bgr++ = bayer[1];
			*bgr++ = adjacent_bayer[1];
			bayer++;
			adjacent_bayer++;

			t0 = (bayer[0] + bayer[2] + adjacent_bayer[1] + 1) / 3;
			t1 = (adjacent_bayer[0] + adjacent_bayer[2] + 1) >> 1;
			*bgr++ = bayer[1];
			*bgr++ = t0;
			*bgr++ = t1;
			bayer++;
			adjacent_bayer++;
		}
	}
	else
	{
		for ( ; width > 2; width -= 2)
		{
			t0 = (bayer[0] + bayer[2] + 1) >> 1;
			*bgr++ = adjacent_bayer[1];
			*bgr++ = bayer[1];
			*bgr++ = t0;
			bayer++;
			adjacent_bayer++;

			t0 = (bayer[0] + bayer[2] + adjacent_bayer[1] + 1) / 3;
			t1 = (adjacent_bayer[0] + adjacent_bayer[2] + 1) >> 1;
			*bgr++ = t1;
			*bgr++ = t0;
			*bgr++ = bayer[1];
			bayer++;
			adjacent_bayer++;
		}
	}

	if (width == 2)
	{
		/* Second to last pixel */
		t0 = (bayer[0] + bayer[2] + 1) >> 1;
		if (blue_line)
		{
			*bgr++ = t0;
			*bgr++ = bayer[1];
			*bgr++ = adjacent_bayer[1];
		}
		else
		{
			*bgr++ = adjacent_bayer[1];
			*bgr++ = bayer[1];
			*bgr++ = t0;
		}
		/* Last pixel */
		t0 = (bayer[1] + adjacent_bayer[2] + 1) >> 1;
		if (blue_line)
		{
			*bgr++ = bayer[2];
			*bgr++ = t0;
			*bgr++ = adjacent_bayer[1];
		}
		else
		{
			*bgr++ = adjacent_bayer[1];
			*bgr++ = t0;
			*bgr++ = bayer[2];
		}
	}
	else
	{
		/* Last pixel */
		if (blue_line)
		{
			*bgr++ = bayer[0];
			*bgr++ = bayer[1];
			*bgr++ = adjacent_bayer[1];
		}
		else
		{
			*bgr++ = adjacent_bayer[1];
			*bgr++ = bayer[1];
			*bgr++ = bayer[0];
		}
	}
}

/* From libdc1394, which on turn was based on OpenCV's Bayer decoding */
static void bayer_to_rgbbgr24(uint8_t *bayer, uint8_t *bgr, int width, int height, bool start_with_green, bool blue_line)
{
	/* render the first line */
	convert_border_bayer_line_to_bgr24(bayer, bayer + width, bgr, width,
		start_with_green, blue_line);
	bgr += width * 3;

	/* reduce height by 2 because of the special case top/bottom line */
	for (height -= 2; height; height--)
	{
		int t0, t1;
		/* (width - 2) because of the border */
		uint8_t *bayerEnd = bayer + (width - 2);

		if (start_with_green)
		{
			/* OpenCV has a bug in the next line, which was
			t0 = (bayer[0] + bayer[width * 2] + 1) >> 1; */
			t0 = (bayer[1] + bayer[width * 2 + 1] + 1) >> 1;
			/* Write first pixel */
			t1 = (bayer[0] + bayer[width * 2] + bayer[width + 1] + 1) / 3;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = t1;
				*bgr++ = bayer[width];
			}
			else
			{
				*bgr++ = bayer[width];
				*bgr++ = t1;
				*bgr++ = t0;
			}

			/* Write second pixel */
			t1 = (bayer[width] + bayer[width + 2] + 1) >> 1;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = bayer[width + 1];
				*bgr++ = t1;
			}
			else
			{
				*bgr++ = t1;
				*bgr++ = bayer[width + 1];
				*bgr++ = t0;
			}
			bayer++;
		}
		else
		{
			/* Write first pixel */
			t0 = (bayer[0] + bayer[width * 2] + 1) >> 1;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = bayer[width];
				*bgr++ = bayer[width + 1];
			}
			else
			{
				*bgr++ = bayer[width + 1];
				*bgr++ = bayer[width];
				*bgr++ = t0;
			}
		}

		if (blue_line)
		{
			for (; bayer <= bayerEnd - 2; bayer += 2)
			{
				t0 = (bayer[0] + bayer[2] + bayer[width * 2] +
					bayer[width * 2 + 2] + 2) >> 2;
				t1 = (bayer[1] + bayer[width] +
					bayer[width + 2] + bayer[width * 2 + 1] +
					2) >> 2;
				*bgr++ = t0;
				*bgr++ = t1;
				*bgr++ = bayer[width + 1];

				t0 = (bayer[2] + bayer[width * 2 + 2] + 1) >> 1;
				t1 = (bayer[width + 1] + bayer[width + 3] +
					1) >> 1;
				*bgr++ = t0;
				*bgr++ = bayer[width + 2];
				*bgr++ = t1;
			}
		}
		else
		{
			for (; bayer <= bayerEnd - 2; bayer += 2)
			{
				t0 = (bayer[0] + bayer[2] + bayer[width * 2] +
					bayer[width * 2 + 2] + 2) >> 2;
				t1 = (bayer[1] + bayer[width] +
					bayer[width + 2] + bayer[width * 2 + 1] +
					2) >> 2;
				*bgr++ = bayer[width + 1];
				*bgr++ = t1;
				*bgr++ = t0;

				t0 = (bayer[2] + bayer[width * 2 + 2] + 1) >> 1;
				t1 = (bayer[width + 1] + bayer[width + 3] +
					1) >> 1;
				*bgr++ = t1;
				*bgr++ = bayer[width + 2];
				*bgr++ = t0;
			}
		}

		if (bayer < bayerEnd)
		{
			/* write second to last pixel */
			t0 = (bayer[0] + bayer[2] + bayer[width * 2] +
				bayer[width * 2 + 2] + 2) >> 2;
			t1 = (bayer[1] + bayer[width] +
				bayer[width + 2] + bayer[width * 2 + 1] +
				2) >> 2;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = t1;
				*bgr++ = bayer[width + 1];
			}
			else
			{
				*bgr++ = bayer[width + 1];
				*bgr++ = t1;
				*bgr++ = t0;
			}
			/* write last pixel */
			t0 = (bayer[2] + bayer[width * 2 + 2] + 1) >> 1;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = bayer[width + 2];
				*bgr++ = bayer[width + 1];
			}
			else
			{
				*bgr++ = bayer[width + 1];
				*bgr++ = bayer[width + 2];
				*bgr++ = t0;
			}
			bayer++;
		}
		else
		{
			/* write last pixel */
			t0 = (bayer[0] + bayer[width * 2] + 1) >> 1;
			t1 = (bayer[1] + bayer[width * 2 + 1] + bayer[width] + 1) / 3;
			if (blue_line)
			{
				*bgr++ = t0;
				*bgr++ = t1;
				*bgr++ = bayer[width + 1];
			}
			else
			{
				*bgr++ = bayer[width + 1];
				*bgr++ = t1;
				*bgr++ = t0;
			}
		}

		/* skip 2 border pixels */
		bayer += 2;

		blue_line = !blue_line;
		start_with_green = !start_with_green;
	}

	/* render the last line */
	convert_border_bayer_line_to_bgr24(bayer + width, bayer, bgr, width, !start_with_green, !blue_line);
}

/*convert bayer raw data to rgb24
* args:
*      pBay: pointer to buffer containing Raw bayer data data
*      pRGB24: pointer to buffer containing rgb24 data
*      width: picture width
*      height: picture height
*      pix_order: bayer pixel order (0=gb/rg   1=gr/bg  2=bg/gr  3=rg/bg)
*/
void bayer_to_rgb24(uint8_t *pBay, uint8_t *pRGB24, int width, int height, int pix_order)
{
	switch (pix_order)
	{
		//conversion functions are build for bgr, by switching b and r lines we get rgb
		case 0: /* gbgbgb... | rgrgrg... (V4L2_PIX_FMT_SGBRG8)*/
			bayer_to_rgbbgr24(pBay, pRGB24, width, height, true, false);
			break;

		case 1: /* grgrgr... | bgbgbg... (V4L2_PIX_FMT_SGRBG8)*/
			bayer_to_rgbbgr24(pBay, pRGB24, width, height, true, true);
			break;

		case 2: /* bgbgbg... | grgrgr... (V4L2_PIX_FMT_SBGGR8)*/
			bayer_to_rgbbgr24(pBay, pRGB24, width, height, false, false);
			break;

		case 3: /* rgrgrg... ! gbgbgb... (V4L2_PIX_FMT_SRGGB8)*/
			bayer_to_rgbbgr24(pBay, pRGB24, width, height, false, true);
			break;

		default: /* default is 0*/
			bayer_to_rgbbgr24(pBay, pRGB24, width, height, true, false);
			break;
	}
}


void rgb_to_yuyv(uint8_t *pyuv, int dstStride, uint8_t *prgb, int srcStride, int width, int height)
{

	int h;
	int dw = dstStride - (width << 1);
	for (h=0;h<height;h++) {
		int i=0;
		for(i=0;i<(width*3);i=i+6)
		{
			/* y */
			*pyuv++ =CLIP(0.299 * (prgb[i] - 128) + 0.587 * (prgb[i+1] - 128) + 0.114 * (prgb[i+2] - 128) + 128);
			/* u */
			*pyuv++ =CLIP(((- 0.147 * (prgb[i] - 128) - 0.289 * (prgb[i+1] - 128) + 0.436 * (prgb[i+2] - 128) + 128) +
				(- 0.147 * (prgb[i+3] - 128) - 0.289 * (prgb[i+4] - 128) + 0.436 * (prgb[i+5] - 128) + 128))/2);
			/* y1 */
			*pyuv++ =CLIP(0.299 * (prgb[i+3] - 128) + 0.587 * (prgb[i+4] - 128) + 0.114 * (prgb[i+5] - 128) + 128);
			/* v*/
			*pyuv++ =CLIP(((0.615 * (prgb[i] - 128) - 0.515 * (prgb[i+1] - 128) - 0.100 * (prgb[i+2] - 128) + 128) +
				(0.615 * (prgb[i+3] - 128) - 0.515 * (prgb[i+4] - 128) - 0.100 * (prgb[i+5] - 128) + 128))/2);
		}
		pyuv += dw;
		prgb += srcStride;
	}
}

void bgr_to_yuyv(uint8_t *pyuv, int dstStride, uint8_t *pbgr, int srcStride, int width, int height)
{
	int h;
	int dw = dstStride - (width << 1);
	for (h=0;h<height;h++) {
		int i=0;
		for(i=0;i<(width*3);i=i+6)
		{
			/* y */
			*pyuv++ =CLIP(0.299 * (pbgr[i+2] - 128) + 0.587 * (pbgr[i+1] - 128) + 0.114 * (pbgr[i] - 128) + 128);
			/* u */
			*pyuv++ =CLIP(((- 0.147 * (pbgr[i+2] - 128) - 0.289 * (pbgr[i+1] - 128) + 0.436 * (pbgr[i] - 128) + 128) +
				(- 0.147 * (pbgr[i+5] - 128) - 0.289 * (pbgr[i+4] - 128) + 0.436 * (pbgr[i+3] - 128) + 128))/2);
			/* y1 */
			*pyuv++ =CLIP(0.299 * (pbgr[i+5] - 128) + 0.587 * (pbgr[i+4] - 128) + 0.114 * (pbgr[i+3] - 128) + 128);
			/* v*/
			*pyuv++ =CLIP(((0.615 * (pbgr[i+2] - 128) - 0.515 * (pbgr[i+1] - 128) - 0.100 * (pbgr[i] - 128) + 128) +
				(0.615 * (pbgr[i+5] - 128) - 0.515 * (pbgr[i+4] - 128) - 0.100 * (pbgr[i+3] - 128) + 128))/2);
		}
		pyuv += dw;
		pbgr += srcStride;
	}
}

//--------------------------------------------------------------------------------------

/*------------------------------- Color space conversions --------------------*/


/* rrrr rggg gggb bbbb */

static inline uint16_t  make565(int red, int green, int blue)
{
    return (uint16_t)( ((red   << 8) & 0xf800) |
                       ((green << 3) & 0x07e0) |
                       ((blue  >> 3) & 0x001f) );
}

#define FIX1P8(x) ((int)((x) * (1<<8)))

static inline int clip(int x)
{
	if (x > 255)
		x = 255;
	if (x < 0)
		x = 0;
	return x;
}


void yuyv_to_rgb565_line (uint8_t *pyuv, uint8_t *prgb, int width)
{
	int l=0;
	int ln = width >> 1;
	uint16_t *p = (uint16_t *)prgb;

	for(l=0; l<ln; l++)
	{	/*iterate every 4 bytes*/

		int u  = pyuv[1] - 128;
		int v  = pyuv[3] - 128;

		int ri = (                       + FIX1P8(1.402)   * v) >> 8;
		int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
		int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

		/* standart: r = y0 + 1.402 (v-128) */
		/* logitech: r = y0 + 1.370705 (v-128) */
		/* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
		/* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
		/* standart: b = y0 + 1.772 (u-128) */
		/* logitech: b = y0 + 1.732446 (u-128) */

		int y0 = pyuv[0];
		*p++ = make565(
			clip(y0 + ri),
			clip(y0 + gi),
			clip(y0 + bi)
			);

		int y1 = pyuv[2];
		*p++ = make565(
			clip(y1 + ri),
			clip(y1 + gi),
			clip(y1 + bi)
			);

		pyuv += 4;
	}
}

/* regular yuv (YUYV) to rgb565*/
void yuyv_to_rgb565 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
	int h=0;
	for(h=0;h<height;h++)
	{
		yuyv_to_rgb565_line (pyuv,prgb,width);
		pyuv += pyuvstride;
		prgb += prgbstride;
	}
}

void yuyv_to_rgb565_rotate180 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
    int h=0;
    pyuv += pyuvstride*(height-1);
    for(h=0;h<height;h++)
    {
        yuyv_to_rgb565_line (pyuv,prgb,width);
        pyuv -= pyuvstride;
        prgb += prgbstride;
    }
}

void yuyv_to_rgb565_line_rotate90 (uint8_t *pyuv, uint8_t *prgb, int width, int line, int black_cols)
{
    int l=0;
    int ln = width >> 1;
    uint16_t *p = (uint16_t *)prgb;
    int col = width - (black_cols + line + 1);
    pyuv += (black_cols*2);
    ln -= black_cols;

    for(l=0; l<ln; l++)
    {   /*iterate every 4 bytes*/

        int u  = pyuv[1] - 128;
        int v  = pyuv[3] - 128;

        int ri = (                       + FIX1P8(1.402)   * v) >> 8;
        int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
        int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

        /* standart: r = y0 + 1.402 (v-128) */
        /* logitech: r = y0 + 1.370705 (v-128) */
        /* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
        /* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
        /* standart: b = y0 + 1.772 (u-128) */
        /* logitech: b = y0 + 1.732446 (u-128) */

        int y0 = pyuv[0];
        *(p + l*2*width + col) = make565(
            clip(y0 + ri),
            clip(y0 + gi),
            clip(y0 + bi)
            );

        int y1 = pyuv[2];
        *(p + (l*2+1)*width + col) = make565(
            clip(y1 + ri),
            clip(y1 + gi),
            clip(y1 + bi)
            );

        pyuv += 4;
    }
}

/* regular yuv (YUYV) to rgb565 with clockwise rotation */
void yuyv_to_rgb565_rotate90 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
    int h=0;
    int black_cols = (width-height)/2;
    memset(prgb, 0, prgbstride*height);

    for(h=0;h<height;h++)
    {
        yuyv_to_rgb565_line_rotate90 (pyuv,prgb,width,h,black_cols);
        pyuv += pyuvstride;
    }
}

void yuyv_to_rgb565_line_rotate270 (uint8_t *pyuv, uint8_t *prgb, int width, int line, int black_cols)
{
    int l=0;
    int ln = width >> 1;
    uint16_t *p = (uint16_t *)prgb;
    int col = black_cols + line;
    pyuv += (black_cols*2);
    ln -= black_cols;

    for(l=ln-1; l>=0; l--)
    {   /*iterate every 4 bytes*/

        int u  = pyuv[1] - 128;
        int v  = pyuv[3] - 128;

        int ri = (                       + FIX1P8(1.402)   * v) >> 8;
        int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
        int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

        /* standart: r = y0 + 1.402 (v-128) */
        /* logitech: r = y0 + 1.370705 (v-128) */
        /* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
        /* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
        /* standart: b = y0 + 1.772 (u-128) */
        /* logitech: b = y0 + 1.732446 (u-128) */

        int y0 = pyuv[0];
        *(p + l*2*width + col) = make565(
            clip(y0 + ri),
            clip(y0 + gi),
            clip(y0 + bi)
            );

        int y1 = pyuv[2];
        *(p + (l*2+1)*width + col) = make565(
            clip(y1 + ri),
            clip(y1 + gi),
            clip(y1 + bi)
            );

        pyuv += 4;
    }
}
/* regular yuv (YUYV) to rgb565 with anti-clockwise rotation */
void yuyv_to_rgb565_rotate270 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
    int h=0;
    int black_cols = (width-height)/2;
    memset(prgb, 0, prgbstride*height);

    for(h=0;h<height;h++)
    {
        yuyv_to_rgb565_line_rotate270 (pyuv,prgb,width,h,black_cols);
        pyuv += pyuvstride;
    }
}

void yuyv_to_rgb24_line (uint8_t *pyuv, uint8_t *prgb, int width)
{
	int l=0;
	int ln = width >> 1;

	for(l=0; l<ln; l++)
	{	/*iterate every 4 bytes*/

		int u  = pyuv[1] - 128;
		int v  = pyuv[3] - 128;

		int ri = (                       + FIX1P8(1.402)   * v) >> 8;
		int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
		int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

		/* standart: r = y0 + 1.402 (v-128) */
		/* logitech: r = y0 + 1.370705 (v-128) */
		/* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
		/* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
		/* standart: b = y0 + 1.772 (u-128) */
		/* logitech: b = y0 + 1.732446 (u-128) */

		int y0 = pyuv[0];
		*prgb++ = clip(y0 + ri);
		*prgb++ = clip(y0 + gi);
		*prgb++ = clip(y0 + bi);

		int y1 = pyuv[2];
		*prgb++ = clip(y1 + ri);
		*prgb++ = clip(y1 + gi);
		*prgb++ = clip(y1 + bi);

		pyuv += 4;
	}
}

/* regular yuv (YUYV) to rgb24*/
void yuyv_to_rgb24 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
	int h=0;
	for(h=0;h<height;h++)
	{
		yuyv_to_rgb24_line (pyuv,prgb,width);
		pyuv += pyuvstride;
		prgb += prgbstride;
	}
}

void yuyv_to_rgb32_line (uint8_t *pyuv, uint8_t *prgb, int width)
{
	int l=0;
	int ln = width >> 1;

	for(l=0; l<ln; l++)
	{	/*iterate every 4 bytes*/

		int u  = pyuv[1] - 128;
		int v  = pyuv[3] - 128;

		int ri = (                       + FIX1P8(1.402)   * v) >> 8;
		int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
		int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

		/* standart: r = y0 + 1.402 (v-128) */
		/* logitech: r = y0 + 1.370705 (v-128) */
		/* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
		/* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
		/* standart: b = y0 + 1.772 (u-128) */
		/* logitech: b = y0 + 1.732446 (u-128) */

		int y0 = pyuv[0];
		*prgb++ = clip(y0 + ri);
		*prgb++ = clip(y0 + gi);
		*prgb++ = clip(y0 + bi);
		prgb++;

		int y1 = pyuv[2];
		*prgb++ = clip(y1 + ri);
		*prgb++ = clip(y1 + gi);
		*prgb++ = clip(y1 + bi);
		prgb++;

		pyuv += 4;
	}
}

/* regular yuv (YUYV) to rgb32*/
void yuyv_to_rgb32 (uint8_t *pyuv, int pyuvstride, uint8_t *prgb,int prgbstride, int width, int height)
{
	int h=0;
	for(h=0;h<height;h++)
	{
		yuyv_to_rgb32_line (pyuv,prgb,width);
		pyuv += pyuvstride;
		prgb += prgbstride;
	}
}

void yuyv_to_bgr24_line (uint8_t *pyuv, uint8_t *pbgr, int width)
{
	int l=0;
	int ln = width >> 1;
	for(l=0;l<ln;l++)
	{	/*iterate every 4 bytes*/
		int u  = pyuv[1] - 128;
		int v  = pyuv[3] - 128;

		int ri = (                       + FIX1P8(1.402)   * v) >> 8;
		int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
		int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

		/* standart: r = y0 + 1.402 (v-128) */
		/* logitech: r = y0 + 1.370705 (v-128) */
		/* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
		/* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
		/* standart: b = y0 + 1.772 (u-128) */
		/* logitech: b = y0 + 1.732446 (u-128) */

		int y0 = pyuv[0];
		*pbgr++ = clip(y0 + ri);
		*pbgr++ = clip(y0 + gi);
		*pbgr++ = clip(y0 + bi);
		pbgr++;

		int y1 = pyuv[2];
		*pbgr++ = clip(y1 + ri);
		*pbgr++ = clip(y1 + gi);
		*pbgr++ = clip(y1 + bi);
		pbgr++;

		pyuv += 4;
	}
}

/* used for rgb video (fourcc="RGB ")           */
/* lines are on correct order                   */
void yuyv_to_bgr24 (uint8_t *pyuv, int pyuvstride, uint8_t *pbgr, int pbgrstride, int width, int height)
{
	int h=0;
	for(h=0;h<height;h++)
	{
		yuyv_to_bgr24_line (pyuv,pbgr,width);
		pyuv += pyuvstride;
		pbgr += pbgrstride;
	}
}

void yuyv_to_bgr32_line (uint8_t *pyuv, uint8_t *pbgr, int width)
{
	int l=0;
	int ln = width >> 1;
	for(l=0;l<ln;l++)
	{	/*iterate every 4 bytes*/
		int u  = pyuv[1] - 128;
		int v  = pyuv[3] - 128;

		int ri = (                       + FIX1P8(1.402)   * v) >> 8;
		int gi = ( - FIX1P8(0.34414) * u - FIX1P8(0.71414) * v) >> 8;
		int bi = ( + FIX1P8(1.772)   * u                      ) >> 8;

		/* standart: r = y0 + 1.402 (v-128) */
		/* logitech: r = y0 + 1.370705 (v-128) */
		/* standart: g = y0 - 0.34414 (u-128) - 0.71414 (v-128)*/
		/* logitech: g = y0 - 0.337633 (u-128)- 0.698001 (v-128)*/
		/* standart: b = y0 + 1.772 (u-128) */
		/* logitech: b = y0 + 1.732446 (u-128) */

		int y0 = pyuv[0];
		*pbgr++ = clip(y0 + ri);
		*pbgr++ = clip(y0 + gi);
		*pbgr++ = clip(y0 + bi);
		pbgr++;

		int y1 = pyuv[2];
		*pbgr++ = clip(y1 + ri);
		*pbgr++ = clip(y1 + gi);
		*pbgr++ = clip(y1 + bi);
		pbgr++;

		pyuv += 4;
	}
}

/* used for rgb video (fourcc="RGB ")           */
/* lines are on correct order                   */
void yuyv_to_bgr32 (uint8_t *pyuv, int pyuvstride, uint8_t *pbgr, int pbgrstride, int width, int height)
{
	int h=0;
	for(h=0;h<height;h++)
	{
		yuyv_to_bgr32_line (pyuv,pbgr,width);
		pyuv += pyuvstride;
		pbgr += pbgrstride;
	}
}

/*	This a custom destination manager for jpeglib that
	enables the use of memory to memory compression.
	See IJG documentation for details.
*/
typedef struct {
	struct jpeg_destination_mgr pub; 	/* base class */
	JOCTET* buffer; 					/* buffer start address */
	size_t bufsize;
	size_t datasize; 					/* final size of compressed data */
	int	   overflowed;
} memory_destination_mgr;
typedef memory_destination_mgr* mem_dest_ptr;


/* This function is called by the library before any data gets written */
METHODDEF(void) init_destination (j_compress_ptr cinfo)
{
	mem_dest_ptr dest = (mem_dest_ptr)cinfo->dest;

	dest->pub.next_output_byte 	= dest->buffer; 	/* set destination buffer */
	dest->pub.free_in_buffer 	= dest->bufsize; 	/* input buffer size */
	dest->datasize = 0; 							/* reset output size */
}

/* This function is called by the library if the buffer fills up */
METHODDEF(boolean) empty_output_buffer (j_compress_ptr cinfo)
{
	mem_dest_ptr dest = (mem_dest_ptr)cinfo->dest;

	// Reinit to the start. Better than crashing
	dest->pub.next_output_byte = (JOCTET*)dest->buffer;
	dest->pub.free_in_buffer   = dest->bufsize;
	dest->overflowed		   = 1;

	return TRUE;
}

/* Usually the library wants to flush output here.
   I will calculate output buffer size here. */

METHODDEF(void) term_destination (j_compress_ptr cinfo)
{
	mem_dest_ptr dest = (mem_dest_ptr)cinfo->dest;
	dest->datasize = dest->bufsize - dest->pub.free_in_buffer;
}

/* Override the default destination manager initialization
   provided by jpeglib. Since we want to use memory-to-memory
   compression, we need to use our own destination manager.
*/
static GLOBAL(void) jpeg_memory_dest (j_compress_ptr cinfo,void* buf,int sz)
{
	mem_dest_ptr dest;

	/* first call for this instance - need to setup */
	if (cinfo->dest == 0) {
		cinfo->dest = (struct jpeg_destination_mgr *)
		(*cinfo->mem->alloc_small) ((j_common_ptr) cinfo, JPOOL_PERMANENT,
			sizeof (memory_destination_mgr));
	}

	dest = (mem_dest_ptr) cinfo->dest;
	dest->bufsize = sz;
	dest->buffer = (JOCTET*)buf;
	dest->datasize = 0;

	/* set method callbacks */
	dest->pub.init_destination 		= init_destination;
	dest->pub.empty_output_buffer 	= empty_output_buffer;
	dest->pub.term_destination 		= term_destination;
}

//rotate jpeg image clockwise by "degree" degree
void jpeg_rotate(uint8_t *src, uint8_t *dst, int size, int degree)
{
  struct jpeg_decompress_struct srcinfo;
  struct jpeg_compress_struct dstinfo;
  struct jpeg_error_mgr jsrcerr, jdsterr;
  jvirt_barray_ptr * src_coef_arrays;
  jvirt_barray_ptr * dst_coef_arrays;
  jpeg_transform_info transformoption;

  /* Initialize the JPEG decompression object with default error handling. */
  srcinfo.err = jpeg_std_error(&jsrcerr);
  jpeg_create_decompress(&srcinfo);
  /* Initialize the JPEG compression object with default error handling. */
  dstinfo.err = jpeg_std_error(&jdsterr);
  jpeg_create_compress(&dstinfo);

  /* Set up default JPEG parameters. */
  dstinfo.err->trace_level = 0;
  switch(degree)
  {
    case 90:
        transformoption.transform = JXFORM_ROT_90;
        break;
    case 180:
        transformoption.transform = JXFORM_FLIP_H;
        break;
    case 270:
        transformoption.transform = JXFORM_ROT_270;
        break;
    default:
        return;
  }

  transformoption.perfect = FALSE;
  transformoption.trim = FALSE;
  transformoption.force_grayscale = FALSE;
  transformoption.crop = FALSE;
  transformoption.slow_hflip = FALSE;

  jsrcerr.trace_level = jdsterr.trace_level;
  srcinfo.mem->max_memory_to_use = dstinfo.mem->max_memory_to_use;

  /* Specify data source for decompression */
  jpeg_mem_src(&srcinfo, src, size);

  /* Read file header */
  (void) jpeg_read_header(&srcinfo, TRUE);

  /* Any space needed by a transform option must be requested before
   * jpeg_read_coefficients so that memory allocation will be done right.
   */
  jtransform_request_workspace(&srcinfo, &transformoption);

  /* Read source file as DCT coefficients */
  src_coef_arrays = jpeg_read_coefficients(&srcinfo);

  /* Initialize destination compression parameters from source values */
  jpeg_copy_critical_parameters(&srcinfo, &dstinfo);

  /* Adjust destination parameters if required by transform options;
   * also find out which set of coefficient arrays will hold the output.
   */
  dst_coef_arrays = jtransform_adjust_parameters(&srcinfo, &dstinfo,
                                                 src_coef_arrays,
                                                 &transformoption);

  /* Specify data destination for compression */
  jpeg_mem_dest(&dstinfo, &dst, (unsigned long *)&size);

  /* Start compressor (note no image data is actually written here) */
  jpeg_write_coefficients(&dstinfo, dst_coef_arrays);

  /* Execute image transformation, if any */
  jtransform_execute_transformation(&srcinfo, &dstinfo,
                                    src_coef_arrays,
                                    &transformoption);

  /* Finish compression and release memory */
  jpeg_finish_compress(&dstinfo);
  jpeg_destroy_compress(&dstinfo);
  (void) jpeg_finish_decompress(&srcinfo);
  jpeg_destroy_decompress(&srcinfo);
}

/* yuyv_to_jpeg
 *  converts an input image in the YUYV format into a jpeg image and puts
 * it in a memory buffer.
 */
int yuyv_to_jpeg(uint8_t* src, uint8_t* dst, int maxsize, int width, int height,int stride,int quality)
{
	// Round height to a multiple of 16:
	height &= (-16);

	// Round width to a multiple of 16
	width &= (-16);

	// Calculate deltaStride
	int dstride = stride - (width << 1);

	int i, j;

	JSAMPROW y[16],cb[8],cr[8];
	JSAMPARRAY data[3];

	struct jpeg_compress_struct cinfo;
	struct jpeg_error_mgr jerr;

	// Allocate memory for line buffers
	y[0] = (JSAMPROW) malloc(sizeof(JSAMPLE) * width * 16);
	cb[0] = (JSAMPROW) malloc(sizeof(JSAMPLE) * (width >> 1) * 8);
	cr[0] = (JSAMPROW) malloc(sizeof(JSAMPLE) * (width >> 1) * 8);

	for (i = 1; i< 16; i++) {
		y[i]  =  y[0] + (i*(sizeof(JSAMPLE) * width));
	}
	for (i = 1; i< 8; i++) {
		cb[i] = cb[0] + (i*(sizeof(JSAMPLE) * (width >> 1)));
		cr[i] = cr[0] + (i*(sizeof(JSAMPLE) * (width >> 1)));
	}

	data[0] = y;
	data[1] = cb;
	data[2] = cr;

	cinfo.err = jpeg_std_error(&jerr);  // errors get written to stderr

	jpeg_create_compress(&cinfo);
	cinfo.image_width = width;
	cinfo.image_height = height;
	cinfo.input_components = 3;
	jpeg_set_defaults (&cinfo);

	jpeg_set_colorspace(&cinfo, JCS_YCbCr);

	cinfo.raw_data_in = TRUE; 			// supply downsampled data
	cinfo.comp_info[0].h_samp_factor = 2;
	cinfo.comp_info[0].v_samp_factor = 2;
	cinfo.comp_info[1].h_samp_factor = 1;
	cinfo.comp_info[1].v_samp_factor = 1;
	cinfo.comp_info[2].h_samp_factor = 1;
	cinfo.comp_info[2].v_samp_factor = 1;

	jpeg_set_quality(&cinfo, quality, TRUE);
	cinfo.dct_method = JDCT_FASTEST;

	jpeg_memory_dest(&cinfo,dst,maxsize);	// data written to mem

	jpeg_start_compress (&cinfo, TRUE);

	uint8_t* yuyv = src;

	for (j=0; j<height; j+=16) {

		JSAMPROW pcb = cb[0];
		JSAMPROW pcr = cr[0];
		JSAMPROW py  = y[0];
		for (i=0; i<8; i++) {

			int x;
			for (x = 0; x < (width>>1); x++) {
				*py++ = *yuyv++;		// Y0
				*pcb++ = (yuyv[0] + yuyv[stride]) >> 1; // U
				yuyv++;
				*py++ = *yuyv++;		// Y1
				*pcr++ = (yuyv[0] + yuyv[stride]) >> 1;	// V
				yuyv++;
			}
			yuyv += dstride;
			for (x = 0; x < (width>>1); x++) {
				*py++ = *yuyv++;		// Y2
				yuyv++;
				*py++ = *yuyv++;		// Y3
				yuyv++;
			}
			yuyv += dstride;
		}
		jpeg_write_raw_data(&cinfo, data, 8*2);
	}

	jpeg_finish_compress(&cinfo);

	// Release memory for line buffers
	free(y[0]);
	free(cb[0]);
	free(cr[0]);

	// Create a buffer with the compressed data
    int fileSize = ((mem_dest_ptr)cinfo.dest)->datasize;

	// Destroy compressor context
	jpeg_destroy_compress(&cinfo);

	return fileSize;
}

// rotate luma image plane 90*
// //
// //             (dst direction)
// //                 ------>
// //      dst -> +-------------+
// //             |^            |
// //             |^ (base dir) |
// //             |^            |
// //     base -> +-------------+ <- endp
// //
static void rotateLumaPlane90(const unsigned char *src, unsigned char *dst,
    size_t size, size_t width, size_t height)
{
    const unsigned char *endp;
    const unsigned char *base;
    size_t j;

    endp = src + size;
    for (base = endp - width; base < endp; base++) {
        src = base;
        for (j = 0; j < height; j++, src -= width)
        {
            *dst++ = *src;
        }

    }
}

//
///////////////////////////////////////////////////////////////////////////////// nv12 chroma plane is interleaved chroma values that map
//         from one pair of chroma to 4 pixels:
////////// Y1 Y2 Y3 Y4
////////// Y5 Y6 Y7 Y8   U1,V1 -> chroma values for block  Y1 Y2
////////// Y9 Ya Yb Yc                                     Y5 Y6
////////// Yd Ye Yf Yg
////////// -----------   U2,V2 -> chroma values for block  Y3 Y4
////////// U1 V1 U2 V2                                     Y7 Y8
////////// U3 V3 U4 V4
//////////
//
//

static void rotateChromaPlane90(const unsigned char *src, unsigned char *dst,
    size_t size, size_t width, size_t height)
{
    // src will start at upper right, moving down to bottom
    // then left 1 col and down...
    // dest will start at end and go to 0


    size_t row = 0;
    int col = (int) width;
    int src_offset = col - 1;
    int dst_offset = (int) size - 2;

    while (src_offset >= 0)
    {
        dst[dst_offset] = src[src_offset];
        dst[dst_offset+1] = src[src_offset+1];
        dst_offset -= 2;

        src_offset += width;
        row++;

        if (row >= height) {
            col -= 2;
            src_offset = col;
            row = 0;
        }
    }
}

void rotate90(uint8_t *frame, int width, int height)
{

    int buf_size;

    buf_size = height*height;
    {
        android::Mutex::Autolock lock(memLock);
        if (gBuf == NULL) {
            //  gBuf = (uint8_t *)malloc(height*width + (height*width)/2);
            gBuf = (uint8_t *)calloc(1, buf_size + buf_size/2);
        }
        if (gYbuf_temp == NULL) {
            gYbuf_temp = (uint8_t *) calloc(1, buf_size);
        }

        if (gUVbuf_temp == NULL) {
            gUVbuf_temp = (uint8_t *) calloc(1, buf_size/2);
        }
    }

    // Only copy the middle pixels, ignoring the extra pixels on left and right of y-plane
    for (int i = 0; i < height; i++) {
        for (int j = 0; j < height; j++) {
            // gYbuf_temp[i*height + j]= frame[i*width + j + (width-height)/2];
            gYbuf_temp[i*height + j]= frame[i*width + j + (width-height)/2];
        }
    }

    //first rotate the y-plane
    rotateLumaPlane90((unsigned char *)gYbuf_temp, gBuf, height*height, height, height);

    //Copy to-be-rotated UV data
    uint8_t* uv_ptr = frame + height*width;
    for (int i = 0; i < height/2; i++) {
        for (int j = 0; j < height; j++) {
            gUVbuf_temp[i*height + j]= uv_ptr[i*width + j + (width-height)/2];
        }
    }

    // then rotate the U-V plane
    rotateChromaPlane90((unsigned char *)gUVbuf_temp,
            gBuf + height*height,
            height*height/2,
            height,
            height/2);

    // Copy the luma component
    for (int i = 0; i < height; i++) {
        for (int j = 0; j < height; j++) {
            frame[i*width + j +(width-height)/2] = gBuf[(height - 1 - i)*height + j] ;
            //frame[i*width + j +(width-height)/2] = gBuf[i*height + j] ;
        }
    }

    // Zero out the superflous pixels
    for (int i = 0; i < height; i++)
        for(int j = 0; j < width; j++)
        {
            if ((j < (width-height)/2) || (j > ((width-height)/2 + height)))
                frame[i*width + j] = 0;
        }


    uv_ptr = &gBuf[height*height];

    uint8_t *dest = frame + height*width;
    //Copy the croma component
    //for (int i = 0; i < height/2; i++) {
    for (int i = 0; i < height/2; i+=1) {
        for (int j = 0; j < height; j++) {
            dest[i*width + j + (width-height)/2] = uv_ptr[(height/2 - 1 - i)*height + j] ;
            //dest[i*width + j + (width-height)/2] = uv_ptr[i*height + j] ;
        }
    }

    return;
}

void rotate270(uint8_t* frame, int width, int height)
{

    {

        android::Mutex::Autolock lock(memLock);
        if(gBuf == NULL){
            gBuf = (uint8_t*)malloc(height*height);
        }
        if(gUbuf == NULL){
            gUbuf = (uint8_t*) malloc(height*width/4);
        }
        if(gVbuf == NULL){
            gVbuf = (uint8_t*) malloc(height*width/4);
        }

        if(gUbuf_temp == NULL){
            gUbuf_temp = (uint8_t*) malloc(height*width/4);
        }
        if(gVbuf_temp == NULL){
            gVbuf_temp = (uint8_t*) malloc(height*width/4);
        }
    }
    //rotating y data
    for (int i = 0; i < height; i++) {
        for (int j = 0; j < height; j++) {
            gBuf[j*height + i ]  = frame[i*width + j + (width-height)/2 ] ;
        }
    }


    for (int i = 0; i < height; i++) {
        for (int j = 0; j < height; j++) {
            //frame[i*width + j +(width-height)/2] = gBuf[i*height + j] ;
            frame[i*width + j +(width-height)/2] = gBuf[(height - 1 - i)*height + j] ;
        }
    }

    // Zero out the superflous pixels
    for (int i = 0; i < height; i++)
        for(int j = 0; j < width; j++)
        {
            if ((j < (width-height)/2) || (j > ((width-height)/2 + height)))
                frame[i*width + j] = 0;
        }

    //rotatting UV data
    uint8_t* uv_ptr = frame + height*width;
    //copying UV data to seprate temp buffers
    for(int i=0; i<width*height/2; i+=2)
    {
        gUbuf_temp[i/2] = uv_ptr[i];
        gVbuf_temp[i/2] = uv_ptr[i + 1];
    }

    //copying UV data from gUbuf_temp removing 40 pixel from left and right
    for(int i=0;i<height/2;i++)
    {
        memcpy(gUbuf + i*height/2, gUbuf_temp + i*width/2 + (width-height)/4, height/2);
        memcpy(gVbuf + i*height/2, gVbuf_temp + i*width/2 + (width-height)/4, height/2);
    }
    //rotating 240X240 UV data
    for (int i=0; i< height/2; i++)
    {
        for (int j=0; j< height/2; j++)
        {
            gUbuf_temp[ i + j*height/2] = gUbuf[ i*height/2 + j];
            gVbuf_temp[ i + j*height/2] = gVbuf[ i*height/2 + j];
        }
    }

    //copying 240X240 back to gUbuf and gVbuf
    for(int i=0;i<height/2;i++)
    {
        //memcpy(gUbuf + i*width/2 + (width-height)/4, gUbuf_temp + i*height/2, height/2);
        //memcpy(gVbuf + i*width/2 + (width-height)/4, gVbuf_temp + i*height/2, height/2);
        memcpy(gUbuf + i*width/2 + (width-height)/4, gUbuf_temp + (height/2 - 1 - i)*height/2, height/2);
        memcpy(gVbuf + i*width/2 + (width-height)/4, gVbuf_temp + (height/2 - 1 - i)*height/2, height/2);
    }

    uint8_t *ut = gUbuf;
    uint8_t *vt = gVbuf;

    //copying data back to frame
    for(int i=0; i<width*height/2; i+=2, ut++, vt++)
    {
        int j = i%width;
        //ignore the superflous pixels
        if ((j < (width-height)/2) || (j >= ((width-height)/2 + height)))
            continue;
        uv_ptr[i] = *ut;
        uv_ptr[i+1] = *vt;
    }
}
void freememory()
{
    {
        android::Mutex::Autolock lock(memLock);
        if(gBuf){
            free(gBuf);
            gBuf = NULL;
        }
        if(gUbuf){
            free(gUbuf);
            gUbuf = NULL;
        }
        if(gVbuf){
            free(gVbuf);
            gVbuf = NULL;
        }
        if(gUbuf_temp){
            free(gUbuf_temp);
            gUbuf_temp = NULL;
        }
        if(gVbuf_temp){
            free(gVbuf_temp);
            gVbuf_temp = NULL;
        }

    if (gYbuf_temp) {
        free(gYbuf_temp);
        gYbuf_temp = NULL;
    }

    if (gUVbuf_temp) {
        free(gUVbuf_temp);
        gUVbuf_temp = NULL;
    }

    }
}

