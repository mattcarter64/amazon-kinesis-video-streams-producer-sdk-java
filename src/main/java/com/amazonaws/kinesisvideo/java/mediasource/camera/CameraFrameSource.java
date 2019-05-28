package com.amazonaws.kinesisvideo.java.mediasource.camera;

import static org.freedesktop.gstreamer.lowlevel.GstBufferAPI.GSTBUFFER_API;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.BufferFlags;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.elements.PlayBin;

import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.logging.Log;
import com.amazonaws.kinesisvideo.common.logging.LogLevel;
import com.amazonaws.kinesisvideo.java.logging.SysOutLogChannel;
import com.amazonaws.kinesisvideo.stream.throttling.DiscreteTimePeriodsThrottler;
import com.sun.jna.NativeLong;

public class CameraFrameSource {

	private Log logger = new Log(new SysOutLogChannel(), LogLevel.VERBOSE, CameraFrameSource.class.getName());

	public static final int DISCRETENESS_HZ = 25;

	private OnFrameDataAvailable onFrameDataAvailable;
	private CameraMediaSourceConfiguration configuration;
	private IpCamera camera;
	private DiscreteTimePeriodsThrottler throttler;
	private boolean isRunning;
	private final ExecutorService executor = Executors.newFixedThreadPool(1);

	private static ArrayBlockingQueue<FrameInfo> processingQueue = new ArrayBlockingQueue<FrameInfo>(1);
	private static StringBuffer videoCaps = new StringBuffer();
	private static Semaphore gotCaps = new Semaphore(2);
	private static Semaphore canSend = new Semaphore(2);
	private static Semaphore gotEOSPlaybin = new Semaphore(1);
	private static Semaphore gotEOSPipeline = new Semaphore(1);
	private static int videoWidth;
	private static int videoHeight;
	private static int numPixels;
	private static boolean sendData = false;

	private static int frameDataSize = (1024 * 1024);

	private Pipeline pipeline;

	public CameraFrameSource(CameraMediaSourceConfiguration configuration, IpCamera camera) {
		this.configuration = configuration;
		this.camera = camera;

		throttler = new DiscreteTimePeriodsThrottler(configuration.getFrameRate(), DISCRETENESS_HZ);
	}

	public void onBytesAvailable(OnFrameDataAvailable createKinesisVideoFrameAndPushToProducer) {
		this.onFrameDataAvailable = createKinesisVideoFrameAndPushToProducer;
	}

	public void start() throws Exception {

		if (isRunning) {
			throw new IllegalStateException("Frame source is already running");
		}

		// TODO
		if (!initGstreamer()) {
			throw new RuntimeException("Unable to initialize ffmpeg");
		}
		//
		// if (!connectAndStartStream()) {
		// throw new RuntimeException("Unable to start stream");
		// }

		isRunning = true;

		startFrameGenerator();
	}

	private boolean initGstreamer() throws Exception {

		logger.info("initGstreamer: start");

		Gst.init();

		logger.info("initGstreamer: gstreamer init complete");

		Bin videoBin = Gst.parseBinFromDescription("appsink name=videoAppSink", true);

		AppSink videoAppSink = (AppSink) videoBin.getElementByName("videoAppSink");

		videoAppSink.set("emit-signals", true);
		videoAppSink.set("async", true);

		// AppSinkListener videoAppSinkListener = new
		// AppSinkListener(processingQueue,
		// videoCaps, gotCaps, logger);
		AppSinkListener videoAppSinkListener = new AppSinkListener(this, videoCaps, gotCaps, logger);

		videoAppSink.connect((AppSink.NEW_SAMPLE) videoAppSinkListener);

		// StringBuilder caps = new
		// StringBuilder("video/x-raw,pixel-aspect-ratio=1/1,");
		StringBuilder caps = new StringBuilder("video/x-h264,stream-format=avc,alignment=au");

		// if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
		// caps.append("format=BGRx");
		// else
		// caps.append("format=xRGB");

		videoAppSink.setCaps(new Caps(caps.toString()));

		logger.info("initGstreamer: app sink initialized");

		PlayBin playbin = new PlayBin("playbin");
		// playbin.setURI(URI.create("rtsp://ip:port/uri"));
		playbin.setURI(URI.create(camera.composeStreamUrl(StreamType.RTSP)));
		playbin.setVideoSink(videoBin);
		// playbin.setAudioSink(null);

		playbin.getBus().connect((Bus.EOS) (source) -> {
			System.out.println("Received the EOS on the playbin!!!");
			gotEOSPlaybin.release();
		});

		logger.info("initGstreamer: playbin initialized");

		gotEOSPlaybin.drainPermits();
		gotCaps.drainPermits();
		playbin.play();

		logger.info("Processing of RTSP feed started, please wait...");

		AppSrc videoAppSrc = null;
		// AppSrcListener videoAppSrcListener = null;

		gotCaps.acquire(1);

		// pipeline = (Pipeline) Gst.parseLaunch("appsrc name=videoAppSrc " + "!
		// videoconvert ! video/x-raw,format=I420 "
		// + "! x264enc ! h264parse " + "! mpegtsmux name=mux " + "! filesink
		// name=filesink ");

		// pipeline = (Pipeline) Gst.parseLaunch("appsrc name=videoAppSrc " + "! rtspsrc
		// " + " ! rtph264depay "
		// + "! capsfilter " + "! appsink name=videoAppSink ");

		// TODO latest
		// pipeline = (Pipeline) Gst.parseLaunch("appsrc name=videoAppSrc " + "! rtspsrc
		// " + " ! rtph264depay "
		// + "! capsfilter " + "! appsink name=videoAppSink ");

		pipeline = (Pipeline) Gst.parseLaunch(
				"rtspsrc name=videoAppSrc " + " ! rtph264depay " + "! capsfilter " + "! appsink name=videoAppSink ");

//		videoAppSrc = (AppSrc) pipeline.getElementByName("videoAppSrc");
//		videoAppSrc.setCaps(new Caps(videoCaps.toString()));
//		videoAppSrc.set("emit-signals", true);

		// videoAppSrcListener = new AppSrcListener(videoQueue,canSend);
		// videoAppSrc.connect((AppSrc.NEED_DATA) videoAppSrcListener);

		pipeline.getBus().connect((Bus.EOS) (source) -> {
			System.out.println("Received the EOS on the pipeline!!!");
			gotEOSPipeline.release();
		});

		// clearQueue(videoQueue);
		processingQueue.clear();
		// videoAppSrcListener.resetSendFlagged();

		gotEOSPipeline.drainPermits();
		canSend.drainPermits();

		sendData = true;

		// pipeline.play();
		//
		// canSend.acquire(1);

		logger.info("initGstreamer: done");

		return true;
	}

	public void stop() {
		// TODO Auto-generated method stub

	}

	private void startFrameGenerator() throws InterruptedException {

		logger.info("startFrameGenerator:");

		pipeline.play();

		canSend.acquire(1);

		// executor.execute(new Runnable() {
		// @Override
		// public void run() {
		// try {
		// generateFrameAndNotifyListener();
		// } catch (Throwable e) {
		// logger.error("startFrameGenerator", e);
		// }
		// }
		// });
	}

	// private void generateFrameAndNotifyListener() throws IOException {
	//
	// logger.info("generateFrameAndNotifyListener: onFrameDataAvailable=" +
	// onFrameDataAvailable);
	//
	// int frameCounter = 0;
	//
	// while (isRunning) {
	// // TODO: Throttler is not limiting first time call when input param
	// // are the same
	// throttler.throttle();
	//
	// if (onFrameDataAvailable != null) {
	// ByteBuffer frameData = createKinesisVideoFrameFromCamera(frameCounter);
	// if (frameData != null) {
	// onFrameDataAvailable.onFrameDataAvailable(frameData);
	// frameCounter++;
	// }
	// }
	//
	// }
	// }

	// private ByteBuffer createKinesisVideoFrameFromCamera(final long index) throws
	// IOException {
	//
	// logger.debug("createKinesisVideoFrameFromCamera: index=" + index);
	//
	// return null;
	// }

	public class FrameInfo {
		private int capacity;
		private int[] pixels;

		private int flags;
		private long duration;
		private long offset;
		private long offsetEnd;
		private long decodeTimestamp;
		private long presentationTimestamp;

		public int getCapacity() {
			return capacity;
		}

		public void setCapacity(int capacity) {
			this.capacity = capacity;
		}

		public int[] getPixels() {
			return pixels;
		}

		public void setPixels(int[] pixels) {
			this.pixels = pixels;
		}

		public int getFlags() {
			return flags;
		}

		public void setFlags(int flags) {
			this.flags = flags;
		}

		public long getDuration() {
			return duration;
		}

		public void setDuration(long duration) {
			this.duration = duration;
		}

		public long getOffset() {
			return offset;
		}

		public void setOffset(long offset) {
			this.offset = offset;
		}

		public long getOffsetEnd() {
			return offsetEnd;
		}

		public void setOffsetEnd(long offsetEnd) {
			this.offsetEnd = offsetEnd;
		}

		public long getDecodeTimestamp() {
			return decodeTimestamp;
		}

		public void setDecodeTimestamp(long decodeTimestamp) {
			this.decodeTimestamp = decodeTimestamp;
		}

		public long getPresentationTimestamp() {
			return presentationTimestamp;
		}

		public void setPresentationTimestamp(long presentationTimestamp) {
			this.presentationTimestamp = presentationTimestamp;
		}
	}

	private static final long HUNDREDS_OF_NANOS_IN_MS = 10 * 1000;
	private static final long FRAME_DURATION_5_MS = 5L;
	private static final long FRAME_DURATION_10_MS = 10L;
	private static final long FRAME_DURATION_20_MS = 20L;

	private class AppSinkListener implements AppSink.NEW_SAMPLE {

		private ArrayBlockingQueue<FrameInfo> queue;
		private StringBuffer caps;
		private Semaphore gotCaps;
		private boolean capsSet;
		private Log logger;
		private CameraFrameSource cameraFrameSource;
		private int frameCounter;

		public AppSinkListener(CameraFrameSource cameraFrameSource, StringBuffer caps, Semaphore gotCaps, Log logger) {
			this.cameraFrameSource = cameraFrameSource;
			this.caps = caps;
			this.gotCaps = gotCaps;
			capsSet = false;
			this.logger = logger;
		}

		@Override

		public FlowReturn newSample(AppSink elem) {
			Sample sample = elem.pullSample();

			logger.debug("newSample: sendData=" + sendData + ", sample=" + sample + ", caps=["
					+ sample.getCaps().toString() + "]");

			if (!capsSet) {
				caps.append(sample.getCaps().toString());
				//
				// Structure capsStruct = sample.getCaps().getStructure(0);
				// videoWidth = capsStruct.getInteger("width");
				// videoHeight = capsStruct.getInteger("height");
				// numPixels = videoWidth * videoHeight;

				capsSet = true;
				gotCaps.release();
			}

			if (sendData) {
				Buffer srcBuffer = sample.getBuffer();

				NativeLong bufferSize = GSTBUFFER_API.gst_buffer_get_size(srcBuffer);

				if (frameDataSize < bufferSize.intValue()) {
					frameDataSize *= 2;
				}

				logger.debug("newSample: bufferSize=" + bufferSize.intValue() + ", frameDataSize=" + frameDataSize);

				// ByteBuffer buffer = ByteBuffer.allocate(frameDataSize);
				//
				// buffer.put(srcBuffer.map(false));
				// buffer.rewind();

				ByteBuffer bb = srcBuffer.map(false);

				logger.debug("newSample: getFlags()=" + srcBuffer.getFlags() + ", getFlags().size="
						+ srcBuffer.getFlags().size() + "delta="
						+ srcBuffer.getFlags().contains(BufferFlags.DELTA_UNIT));

				CameraFrameInfo frameInfo = new CameraFrameInfo(frameCounter++, bb /* buffer */,
						!srcBuffer.getFlags().contains(BufferFlags.DELTA_UNIT), srcBuffer.getPresentationTimestamp(),
						srcBuffer.getDecodeTimestamp(), FRAME_DURATION_5_MS * HUNDREDS_OF_NANOS_IN_MS);

				throttler.throttle();

				if (onFrameDataAvailable != null) {
					// ByteBuffer frameData = createKinesisVideoFrameFromCamera(frameCounter);
					logger.debug("newSample: sending data");

					onFrameDataAvailable.onFrameDataAvailable(frameInfo);
					frameCounter++;
				}

				srcBuffer.unmap();
			}

			sample.dispose();

			return FlowReturn.OK;
		}
	}

}
