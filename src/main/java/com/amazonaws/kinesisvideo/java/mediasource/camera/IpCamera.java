package com.amazonaws.kinesisvideo.java.mediasource.camera;

public class IpCamera {

	private String ipAddress;
	private String username;
	private String password;
	private String mjpegUrl;
	private String hlsUrl;
	private String rtspUrl;

	public IpCamera(String ipAddress, String username, String password, String mjpegUrl, String hlsUrl,
			String rtspUrl) {
		this.ipAddress = ipAddress;
		this.username = username;
		this.password = password;
		this.mjpegUrl = mjpegUrl;
		this.hlsUrl = hlsUrl;
		this.rtspUrl = rtspUrl;
	}

	public void open() {
		// TODO Auto-generated method stub

	}

	public void close() {
		// TODO Auto-generated method stub

	}

	public String composeStreamUrl(StreamType type) {

		switch (type) {
		case HLS:
			break;
		case MJPEG:
			break;
		case RTSP:
			return "rtsp://" + (username != null ? username + ":" + password + "@" : "") + ipAddress + rtspUrl;
		default:
			break;
		}

		return null;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public void setIpAddress(String ipAddress) {
		this.ipAddress = ipAddress;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getMjpegUrl() {
		return mjpegUrl;
	}

	public void setMjpegUrl(String mjpegUrl) {
		this.mjpegUrl = mjpegUrl;
	}

	public String getHlsUrl() {
		return hlsUrl;
	}

	public void setHlsUrl(String hlsUrl) {
		this.hlsUrl = hlsUrl;
	}

	public String getRtspUrl() {
		return rtspUrl;
	}

	public void setRtspUrl(String rtspUrl) {
		this.rtspUrl = rtspUrl;
	}

}
