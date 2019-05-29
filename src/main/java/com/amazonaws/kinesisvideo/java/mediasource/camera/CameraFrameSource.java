package com.amazonaws.kinesisvideo.java.mediasource.camera;

import static org.freedesktop.gstreamer.lowlevel.GstBufferAPI.GSTBUFFER_API;
import static org.freedesktop.gstreamer.lowlevel.GstStructureAPI.GSTSTRUCTURE_API;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.BufferFlags;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.GstObject;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.lowlevel.GValueAPI.GValue;
import org.freedesktop.gstreamer.lowlevel.MainLoop;

import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.logging.Log;
import com.amazonaws.kinesisvideo.common.logging.LogLevel;
import com.amazonaws.kinesisvideo.java.logging.SysOutLogChannel;
import com.amazonaws.kinesisvideo.stream.throttling.DiscreteTimePeriodsThrottler;
import com.sun.jna.NativeLong;

public class CameraFrameSource {

	private Log logger = new Log(new SysOutLogChannel(), LogLevel.VERBOSE, CameraFrameSource.class.getName());

	private static final int DISCRETENESS_HZ = 25;
	private static final long HUNDREDS_OF_NANOS_IN_MS = 10 * 1000;
	private static final long FRAME_DURATION_5_MS = 5L;
	private static final long FRAME_DURATION_10_MS = 10L;
	private static final long FRAME_DURATION_20_MS = 20L;

	private CameraFrameDataAvailable onFrameDataAvailable;
	private CameraMediaSourceConfiguration configuration;
	private IpCamera camera;
	private DiscreteTimePeriodsThrottler throttler;
	private boolean isRunning;
	private static StringBuffer videoCaps = new StringBuffer();
	private static Semaphore gotCaps = new Semaphore(2);
	private static Semaphore canSend = new Semaphore(2);
	private static boolean sendData = false;

	final MainLoop loop = new MainLoop();
	private Pipeline pipeline;

	public CameraFrameSource(CameraMediaSourceConfiguration configuration, IpCamera camera) {
		this.configuration = configuration;
		this.camera = camera;

		throttler = new DiscreteTimePeriodsThrottler(configuration.getFrameRate(), DISCRETENESS_HZ);
	}

	public void setOnFrameDataAvailable(CameraFrameDataAvailable onFrameDataAvailable) {
		this.onFrameDataAvailable = onFrameDataAvailable;
	}

	public void start() throws Exception {

		if (isRunning) {
			throw new IllegalStateException("Frame source is already running");
		}

		if (!initGstreamer()) {
			throw new RuntimeException("Unable to initialize gstreamer");
		}

		isRunning = true;

		startFrameGenerator();
	}

	private boolean initGstreamer() throws Exception {

		logger.info("initGstreamer: start");

		Gst.init();

		logger.info("initGstreamer: gstreamer init complete");

		AppSink appsink = (AppSink) ElementFactory.make("appsink", "video-output");

		Element source = ElementFactory.make("rtspsrc", "source");

		Element depay = ElementFactory.make("rtph264depay", "depay");

		Element filter = ElementFactory.make("capsfilter", "encoder_filter");

		StringBuilder caps = new StringBuilder("video/x-h264,stream-format=avc,alignment=au");

		filter.set("caps", new Caps(caps.toString()));

		pipeline = new Pipeline();

		Bus bus = pipeline.getBus();
		bus.connect(new Bus.EOS() {

			@Override
			public void endOfStream(GstObject source) {
				System.out.println("Reached end of stream");
				loop.quit();
			}

		});

		bus.connect(new Bus.ERROR() {

			@Override
			public void errorMessage(GstObject source, int code, String message) {
				System.out.println("Error detected");
				System.out.println("Error source: " + source.getName());
				System.out.println("Error code: " + code);
				System.out.println("Message: " + message);
				loop.quit();
			}
		});

		source.set("location", camera.composeStreamUrl(StreamType.RTSP));
		source.set("short-header", true);
		source.set("protocols", 4);

		PadAddedListener padAddedListener = new PadAddedListener(depay);

		source.connect(padAddedListener);

		appsink.set("emit-signals", true);
		appsink.set("sync", false);

		AppSinkListener videoAppSinkListener = new AppSinkListener(videoCaps, gotCaps, logger);

		appsink.connect((AppSink.NEW_SAMPLE) videoAppSinkListener);

		pipeline.addMany(source, depay, filter, appsink);

		Element.linkMany(depay, filter, appsink);

		sendData = true;

		logger.info("initGstreamer: done");

		return true;
	}

	public void stop() {
		// TODO Auto-generated method stub

	}

	private void startFrameGenerator() throws InterruptedException {

		logger.info("startFrameGenerator:");

		pipeline.play();

		loop.run();

		canSend.acquire(1);
	}

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

	private class PadAddedListener implements Element.PAD_ADDED {

		private Element dest;

		public PadAddedListener(Element dest) {
			this.dest = dest;
		}

		@Override
		public void padAdded(Element element, Pad pad) {
			element.link(dest);

			logger.debug("padAdded: linked pad" + pad);
		}

	}

	private class AppSinkListener implements AppSink.NEW_SAMPLE {

		private StringBuffer caps;
		private Semaphore gotCaps;
		private boolean capsSet;
		private Log logger;
		private int frameCounter;

		public AppSinkListener(StringBuffer caps, Semaphore gotCaps, Log logger) {
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

				Structure codecData = sample.getCaps().getStructure(0);

				GValue value = GSTSTRUCTURE_API.gst_structure_get_value(codecData, "codec_data");

				if (value != null) {
					logger.debug(
							"newSample: gvalue=" + value + ", type=" + value.getType() + ", value=" + value.getValue());

					Buffer buffer = (Buffer) value.getValue();

					NativeLong bufferSize = GSTBUFFER_API.gst_buffer_get_size(buffer);

					ByteBuffer bb = buffer.map(false);

					byte[] codecBytes = new byte[bufferSize.intValue()];

					bb.get(codecBytes);

					logger.debug("newSample: buffer=" + buffer + ", size=" + bufferSize.intValue() + ", bb=" + bb
							+ ", hasArray=" + bb.hasArray() + ", codecBytes=" + codecBytes);

					onFrameDataAvailable.onCodecDataAvailable(codecBytes);
				}

				capsSet = true;
				gotCaps.release();
			}

			if (sendData) {
				Buffer srcBuffer = sample.getBuffer();

				logBuffer("newSample", srcBuffer);

				ByteBuffer bb = srcBuffer.map(false);

				CameraFrameInfo frameInfo = new CameraFrameInfo(frameCounter++, bb /* buffer */,
						!srcBuffer.getFlags().contains(BufferFlags.DELTA_UNIT), srcBuffer.getPresentationTimestamp(),
						srcBuffer.getDecodeTimestamp() != -1 ? srcBuffer.getDecodeTimestamp()
								: srcBuffer.getPresentationTimestamp(),
						FRAME_DURATION_5_MS * HUNDREDS_OF_NANOS_IN_MS);

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

		private void logBuffer(String tag, Buffer buffer) {

			NativeLong bufferSize = GSTBUFFER_API.gst_buffer_get_size(buffer);

			logger.debug(tag + "buffer: bufferSize=" + bufferSize + ", offset=" + buffer.getOffset() + ", pts="
					+ buffer.getPresentationTimestamp() + ", dts=" + buffer.getDecodeTimestamp() + ", duration="
					+ buffer.getDuration() + ", flags=" + buffer.getFlags() + ", delta="
					+ buffer.getFlags().contains(BufferFlags.DELTA_UNIT));

		}
	}

}
