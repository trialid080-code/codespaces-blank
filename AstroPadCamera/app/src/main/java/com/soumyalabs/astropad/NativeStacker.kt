package com.soumyalabs.astropad
object NativeStacker { init { System.loadLibrary("astropad_native") }; external fun meanStack(frames:Array<FloatArray>):FloatArray }
