package com.amazonaws.kinesisvideo.java.mediasource.camera;

public interface CameraFrameDataAvailable {
	
    void onFrameDataAvailable(final CameraFrameInfo frameInfo);

    void onCodecDataAvailable(final byte[] codecData);
}
