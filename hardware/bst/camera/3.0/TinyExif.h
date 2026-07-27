#ifndef _TINY_EXIF_H
#define _TINY_EXIF_H

#define TAG_MODEL 0x0110
#define TAG_MAKE 0x010f
#define TAG_FOCALLENGTH 0x920a
#define TAG_DATETIME 0x0132
#define TAG_IMAGE_WIDTH 0x0100
#define TAG_IMAGE_LENGTH 0x0101
#define TAG_ORIENTATION 0x0112
#define TAG_GPS_LATITUDE_REF 0x0001
#define TAG_GPS_LATITUDE 0x0002
#define TAG_GPS_LONGITUDE_REF 0x0003
#define TAG_GPS_LONGITUDE 0x0004
#define TAG_GPS_ALTITUDE_REF 0x0005
#define TAG_GPS_ALTITUDE 0x0006
#define TAG_GPS_MAP_DATUM 0x0012
#define TAG_GPS_PROCESSING_METHOD 0x001b
#define TAG_GPS_VERSION_ID 0x0000
#define TAG_GPS_TIMESTAMP 0x0007
#define TAG_GPS_DATESTAMP 0x001d

const static char ExifAsciiPrefix[] = {0x41, 0x53, 0x43, 0x49, 0x49, 0x0, 0x0, 0x0};

typedef struct st_IFDEle {
    uint16_t tag;
    uint32_t val1;
    uint32_t val2;
    uint32_t val3;
    uint32_t val4;
    uint32_t val5;
    uint32_t val6;
    char* strVal;
} IFDEle;

int InsertEXIFAndThumbnail(IFDEle* pIFDEle,
                           uint32_t eleNum,
                           uint8_t* pThumb,
                           uint32_t thumbSize,
                           uint8_t* pMain,
                           uint32_t mainSize,
                           uint8_t* pDst,
                           uint32_t dstSize,
                           uint32_t *pRequestSize);

#endif
