#pragma once
#include "SensorGlucoseData.hpp"
#include "gltype.hpp"
#include <string_view>
template <typename T>
inline void	addstrview(char *&uitptr,const T indata) {
	memcpy(uitptr,indata.data(),indata.size());
	uitptr+=indata.size();
	}

template <class T, size_t N>
inline static constexpr void addar(char *&uitptr,const T (&array)[N]) {
	constexpr const int len=N-1;
	memcpy(uitptr,array,len);
	uitptr+=len;
	}

inline double getdelta(float change) {
	static constexpr const double deltatimes=5.0;
	 return isnan(change)?0:change*deltatimes; //json has no nan. This is obviously wrong, I don't know what else to do. Return null?
	 }

inline std::string_view fixedsensorview(const sensorname_t *sensorname) {
	if(!sensorname)
		return {};
	const char *name=sensorname->data();
	if(name[0]=='X'&&name[1]=='-') {
		size_t len=0;
		for(;len<32;len++) {
			const unsigned char ch=static_cast<unsigned char>(name[len]);
			if(ch==0||ch<0x20||ch=='/'||ch=='\\'||ch=='"')
				break;
			}
		if(len>=sensorname->size())
			return {name,len};
		}
	return {name,sensorname->size()};
	}

inline void addjsonescaped(char *&outptr,std::string_view value) {
	static constexpr char hex[]="0123456789abcdef";
	for(unsigned char ch: value) {
		switch(ch) {
			case '"':
				*outptr++='\\';
				*outptr++='"';
				break;
			case '\\':
				*outptr++='\\';
				*outptr++='\\';
				break;
			case '\b':
				*outptr++='\\';
				*outptr++='b';
				break;
			case '\f':
				*outptr++='\\';
				*outptr++='f';
				break;
			case '\n':
				*outptr++='\\';
				*outptr++='n';
				break;
			case '\r':
				*outptr++='\\';
				*outptr++='r';
				break;
			case '\t':
				*outptr++='\\';
				*outptr++='t';
				break;
			default:
				if(ch<0x20) {
					*outptr++='\\';
					*outptr++='u';
					*outptr++='0';
					*outptr++='0';
					*outptr++=hex[ch>>4];
					*outptr++=hex[ch&0x0F];
					}
				else {
					*outptr++=static_cast<char>(ch);
					}
			}
		}
	}

inline void addjsonstring(char *&outptr,std::string_view value) {
	*outptr++='"';
	addjsonescaped(outptr,value);
	*outptr++='"';
	}

int resolveExportedMgdl(const SensorGlucoseData *sens, const ScanData *val,
                        const sensorname_t *sensorname);
const ScanData *makeExportedScan(const SensorGlucoseData *sens,
                                 const ScanData *val,
                                 const sensorname_t *sensorname,
                                 ScanData &storage);
int getExchangeOutputIntervalSeconds();
