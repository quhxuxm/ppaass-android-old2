#include "com_ppaass_agent_activity_JniTest.h"

JNIEXPORT jstring JNICALL Java_com_ppaass_agent_activity_JniTest_getStringFromNDK
  (JNIEnv *env, jobject obj){
     return (*env)->NewStringUTF(env,"Hellow World，这是隔壁老李头的NDK的第一行代码");
  }
