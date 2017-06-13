package cn.superman.sandbox.core.systemOutStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class CacheOutputStream extends OutputStream {

	private volatile ThreadLocal<ByteArrayOutputStream> localBytesCache = new ThreadLocal<ByteArrayOutputStream>() {
		@Override
		protected ByteArrayOutputStream initialValue() {
			return new ByteArrayOutputStream();
		}

	};

	@Override
	public void write(int b) throws IOException {
		ByteArrayOutputStream byteBufferStream = localBytesCache.get();
		byteBufferStream.write(b);
	}

	public byte[] removeBytes(long threadId) {
		ByteArrayOutputStream byteBufferStream = localBytesCache.get();

		if (byteBufferStream == null) {
			return new byte[0];
		}
		byte[] result = byteBufferStream.toByteArray();
		// 因为这个可能以后还可以重用（因为线程时有反复重用的，所以这里只需要将里面的内容清空就可以了）
		byteBufferStream.reset();
		return result;
	}
}
