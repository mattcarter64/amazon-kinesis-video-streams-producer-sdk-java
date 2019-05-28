package com.amazonaws.kinesisvideo.java.mediasource.camera;

import java.nio.ByteBuffer;

public class CameraFrameInfo {

	private long frameIndex;
	private ByteBuffer buffer;
	private boolean keyFrame;
	private long pts;
	private long dts;
	private long duration;

	public CameraFrameInfo(long frameIndex, ByteBuffer buffer, boolean keyFrame, long pts, long dts, long duration) {
		super();
		this.frameIndex = frameIndex;
		this.buffer = buffer;
		this.keyFrame = keyFrame;
		this.pts = pts;
		this.dts = dts;
		this.duration = duration;
	}

	public long getFrameIndex() {
		return frameIndex;
	}

	public ByteBuffer getBuffer() {
		return buffer;
	}

	public boolean isKeyFrame() {
		return keyFrame;
	}

	public long getPts() {
		return pts;
	}

	public long getDts() {
		return dts;
	}

	public long getDuration() {
		return duration;
	}

	@Override
	public String toString() {
		return "CameraFrameInfo [frameIndex=" + frameIndex + ", buffer=" + buffer + ", keyFrame=" + keyFrame + ", pts="
				+ pts + ", dts=" + dts + ", duration=" + duration + "]";
	}
}
