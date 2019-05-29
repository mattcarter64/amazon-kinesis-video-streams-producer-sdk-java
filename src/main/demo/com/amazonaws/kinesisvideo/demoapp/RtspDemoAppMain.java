package com.amazonaws.kinesisvideo.demoapp;

import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.demoapp.auth.AuthHelper;
import com.amazonaws.kinesisvideo.demoapp.contants.DemoTrackInfos;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.java.mediasource.camera.CameraMediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.camera.IpCamera;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.regions.Regions;

public final class RtspDemoAppMain {

	private static final String STREAM_NAME = "jc4728-rtsp-stream";
	private static final int FPS_25 = 25;
//	private static final int RETENTION_ONE_HOUR = 1;
	private static final int RETENTION_TWENTY_FOUR_HOURS = 24;

	private RtspDemoAppMain() {
		throw new UnsupportedOperationException();
	}

	public static void main(final String[] args) {
		try {
			// create Kinesis Video high level client
			final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
					.createKinesisVideoClient(Regions.US_EAST_1, AuthHelper.getSystemPropertiesCredentialsProvider());

			final MediaSource mediaSource = createCameraMediaSource();

			// register media source with Kinesis Video Client
			kinesisVideoClient.registerMediaSource(mediaSource);

			// start streaming
			mediaSource.start();
		} catch (final KinesisVideoException e) {
			throw new RuntimeException(e);
		}
	}

	private static MediaSource createCameraMediaSource() {

		IpCamera camera = new IpCamera("10.87.1.49", "openhabian", "h0meaut0mAt10n", null, null, "/profile1/media.smp");

		final CameraMediaSourceConfiguration configuration = new CameraMediaSourceConfiguration.Builder()
				.withFrameRate(FPS_25).withRetentionPeriodInHours(RETENTION_TWENTY_FOUR_HOURS).withCameraId("cam1")
				.withIsEncoderHardwareAccelerated(false).withEncodingMimeType("video/avc")
				.withNalAdaptationFlags(StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE)
				.withIsAbsoluteTimecode(false).withEncodingBitRate(200000).withHorizontalResolution(1280)
				.withVerticalResolution(480).withTrackInfoList(DemoTrackInfos.createTrackInfoList()).build();

		final CameraMediaSource mediaSource = new CameraMediaSource(STREAM_NAME, camera);

		mediaSource.configure(configuration);

		return mediaSource;
	}

}
