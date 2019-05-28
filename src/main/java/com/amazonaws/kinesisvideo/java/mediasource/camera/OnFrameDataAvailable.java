package com.amazonaws.kinesisvideo.java.mediasource.camera;

public interface OnFrameDataAvailable {
    void onFrameDataAvailable(final CameraFrameInfo frameInfo);
}
