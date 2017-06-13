package cn.superman.sandbox.core.systemInStream;

import java.io.IOException;
import java.io.InputStream;

public class ThreadInputStream extends InputStream {
	private volatile ThreadLocal<InputStream> localIn = new ThreadLocal<InputStream>();

	@Override
	public int read() throws IOException {
		return localIn.get().read();
	}

	public void setThreadIn(InputStream in) {
		localIn.set(in);
	}

	public void removeAndCloseThreadIn() {
		InputStream stream = localIn.get();
		localIn.remove();
		try {
			if (stream != null) {
				stream.close();
				stream = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
