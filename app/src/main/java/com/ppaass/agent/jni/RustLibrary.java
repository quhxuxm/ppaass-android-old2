package com.ppaass.agent.jni;

public class RustLibrary {
    static {
        System.loadLibrary("rust");
    }

    public static native String handleInputString(String inputMessage);

    public static native ExampleNativeObject handleInputObject(ExampleNativeObject input);
}