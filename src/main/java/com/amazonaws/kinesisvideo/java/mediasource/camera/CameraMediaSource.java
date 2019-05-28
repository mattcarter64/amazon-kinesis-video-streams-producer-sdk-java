package com.amazonaws.kinesisvideo.java.mediasource.camera;

import static com.amazonaws.kinesisvideo.producer.StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_BITRATE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_GOP_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_REPLAY_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_STALENESS_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_TIMESCALE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.KEYFRAME_FRAGMENTATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NOT_ADAPTIVE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NO_KMS_KEY_ID;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECALCULATE_METRICS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECOVER_ON_FAILURE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RELATIVE_TIMECODES;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.REQUEST_FRAGMENT_ACKS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RETENTION_ONE_HOUR;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.USE_FRAME_TIMECODES;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.VERSION_ZERO;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.VIDEO_CODEC_ID;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.VIDEO_CONTENT_TYPE;

import java.util.concurrent.TimeUnit;

import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.common.logging.Log;
import com.amazonaws.kinesisvideo.common.logging.LogLevel;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceConfiguration;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceSink;
import com.amazonaws.kinesisvideo.java.logging.SysOutLogChannel;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.StreamCallbacks;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;
import com.amazonaws.kinesisvideo.producer.Time;

public class CameraMediaSource implements MediaSource {

	private Log logger = new Log(new SysOutLogChannel(), LogLevel.VERBOSE, CameraMediaSource.class.getName());

	private static final int FRAME_FLAG_KEY_FRAME = 1;
	private static final int FRAME_FLAG_NONE = 0;
	private static final long HUNDREDS_OF_NANOS_IN_MS = 10 * 1000;
	private static final long FRAME_DURATION_10_MS = 10L;
	private static final long FRAME_DURATION_20_MS = 20L;

	private String streamName;

	private CameraMediaSourceConfiguration configuration;
	private MediaSourceState mediaSourceState;
	private MediaSourceSink mediaSourceSink;
	private CameraFrameSource cameraFrameSource;
	private int frameIndex;
	private IpCamera camera = null;

	public CameraMediaSource(String streamName, IpCamera camera) {
		this.streamName = streamName;
		this.camera = camera;
	}

	@Override
	public MediaSourceState getMediaSourceState() {
		return mediaSourceState;
	}

	@Override
	public MediaSourceConfiguration getConfiguration() {
		return configuration;
	}

	@Override
	public void initialize(MediaSourceSink mediaSourceSink) throws KinesisVideoException {
		this.mediaSourceSink = mediaSourceSink;
	}

	@Override
	public void configure(MediaSourceConfiguration configuration) {

		if (!(configuration instanceof CameraMediaSourceConfiguration)) {
			throw new IllegalStateException("Configuration must be an instance of OpenCvMediaSourceConfiguration");
		}
		this.configuration = (CameraMediaSourceConfiguration) configuration;
		this.frameIndex = 0;

	}

	@Override
	public void start() throws KinesisVideoException {

		mediaSourceState = MediaSourceState.RUNNING;

		cameraFrameSource = new CameraFrameSource(configuration, camera);
		cameraFrameSource.onBytesAvailable(createKinesisVideoFrameAndPushToProducer());

		try {
			cameraFrameSource.start();
		} catch (Exception e) {
			throw new KinesisVideoException(e);
		}
	}

	private OnFrameDataAvailable createKinesisVideoFrameAndPushToProducer() {

		return new OnFrameDataAvailable() {
			@Override
			public void onFrameDataAvailable(final CameraFrameInfo info) {
				final long currentTimeMs = System.currentTimeMillis();

				final int flags = info.isKeyFrame() ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;

				if (info != null) {

					final KinesisVideoFrame frame = new KinesisVideoFrame((int) info.getFrameIndex(), flags,
							info.getDts() / 100, info.getPts() / 100, info.getDuration(), info.getBuffer());

					logger.debug("onFrameDataAvailable: currentTimeMs=" + currentTimeMs + " , flags=" + flags
							+ ", frame=[" + frame + ", size=" + frame.getSize() + "], info=[" + info + "]");

					if (frame.getSize() == 0) {
						return;
					}

					putFrame(frame);
				} else {
					System.out.println("Data not received from frame");
				}

			}
		};
	}

	private void putFrame(final KinesisVideoFrame kinesisVideoFrame) {
		try {
			mediaSourceSink.onFrame(kinesisVideoFrame);
		} catch (final KinesisVideoException ex) {
			throw new RuntimeException(ex);
		}
	}

	private boolean isKeyFrame() {
		return frameIndex % configuration.getFrameRate() == 0;
	}

	@Override
	public void stop() throws KinesisVideoException {

		if (cameraFrameSource != null) {
			cameraFrameSource.stop();
		}

		mediaSourceState = MediaSourceState.STOPPED;

		camera.close();
	}

	@Override
	public boolean isStopped() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void free() throws KinesisVideoException {
		// TODO Auto-generated method stub

	}

	@Override
	public StreamInfo getStreamInfo() {
		return new StreamInfo(VERSION_ZERO, streamName, StreamInfo.StreamingType.STREAMING_TYPE_REALTIME,
				VIDEO_CONTENT_TYPE, NO_KMS_KEY_ID, RETENTION_ONE_HOUR, NOT_ADAPTIVE, TimeUnit.SECONDS.toMillis(60),
				DEFAULT_GOP_DURATION, KEYFRAME_FRAGMENTATION, USE_FRAME_TIMECODES, RELATIVE_TIMECODES,
				REQUEST_FRAGMENT_ACKS, RECOVER_ON_FAILURE, VIDEO_CODEC_ID, "test-track",
				/* DEFAULT_BITRATE */(4 * 1024 * 1024), configuration.getFrameRate(),
				(120 * Time.HUNDREDS_OF_NANOS_IN_A_SECOND), (40 * Time.HUNDREDS_OF_NANOS_IN_A_SECOND),
				DEFAULT_STALENESS_DURATION, DEFAULT_TIMESCALE, RECALCULATE_METRICS, null,
				new Tag[] { new Tag("device", "Test Device"), new Tag("stream", "Test Stream") },
				NAL_ADAPTATION_FLAG_NONE);
	}

	// @Override
	// public StreamInfo getStreamInfo() throws KinesisVideoException {
	// return new StreamInfo(VERSION_ZERO,
	// streamName,
	// StreamInfo.StreamingType.STREAMING_TYPE_REALTIME,
	// configuration.getContentType(),
	// NO_KMS_KEY_ID,
	// configuration.getRetentionPeriodInHours() * HUNDREDS_OF_NANOS_IN_AN_HOUR,
	// NOT_ADAPTIVE,
	// configuration.getLatencyPressure(),
	// DEFAULT_GOP_DURATION,
	// KEYFRAME_FRAGMENTATION,
	// USE_FRAME_TIMECODES,
	// configuration.isAbsoluteTimecode(),
	// REQUEST_FRAGMENT_ACKS,
	// RECOVER_ON_FAILURE,
	// DEFAULT_BITRATE,
	// configuration.getFps(),
	// configuration.getBufferDuration(),
	// configuration.getReplayDuration(),
	// configuration.getStalenessDuration(),
	// configuration.getTimecodeScale(),
	// RECALCULATE_METRICS,
	// null,
	// configuration.getNalAdaptationFlag(),
	// null,
	// configuration.getTrackInfoList());
	// }

	// @Override
	// public StreamInfo getStreamInfo() throws KinesisVideoException {
	// return new StreamInfo(VERSION_ZERO, streamName,
	// StreamInfo.StreamingType.STREAMING_TYPE_REALTIME,
	// configuration.getContentType(), NO_KMS_KEY_ID,
	// configuration.getRetentionPeriodInHours() * HUNDREDS_OF_NANOS_IN_AN_HOUR,
	// NOT_ADAPTIVE,
	// configuration.getLatencyPressure(), DEFAULT_GOP_DURATION,
	// KEYFRAME_FRAGMENTATION, USE_FRAME_TIMECODES,
	// configuration.isAbsoluteTimecode(), REQUEST_FRAGMENT_ACKS,
	// RECOVER_ON_FAILURE, DEFAULT_BITRATE,
	// configuration.getFps(), configuration.getBufferDuration(),
	// configuration.getReplayDuration(),
	// configuration.getStalenessDuration(), configuration.getTimecodeScale(),
	// RECALCULATE_METRICS, null,
	// configuration.getNalAdaptationFlag(), null,
	// configuration.getTrackInfoList());
	// }

	@Override
	public MediaSourceSink getMediaSourceSink() {
		return mediaSourceSink;
	}

	@Override
	public StreamCallbacks getStreamCallbacks() {
		// TODO Auto-generated method stub
		return null;
	}

}
