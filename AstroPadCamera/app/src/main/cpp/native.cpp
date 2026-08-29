#include <jni.h>
#include <vector>
#include <algorithm>
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_soumyalabs_astropad_NativeStacker_meanStack(JNIEnv* env,jclass, jobjectArray frames){
    // First native milestone: API placeholder for the future LibRaw/Siril-derived
    // linear RAW registration + sigma-clipped stacking pipeline.
    jsize n=env->GetArrayLength(frames); jfloatArray out=env->NewFloatArray(n); std::vector<float> v(n); for(int i=0;i<n;i++)v[i]=i; env->SetFloatArrayRegion(out,0,n,v.data()); return out;
}
