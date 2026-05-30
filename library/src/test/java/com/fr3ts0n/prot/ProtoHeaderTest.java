package com.fr3ts0n.prot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for ProtoHeader.getParamInt() boundary handling.
 *
 * Guards against silent buffer over-read: a malformed / truncated response must
 * raise IndexOutOfBoundsException rather than reading past the buffer end.
 */
class ProtoHeaderTest
{
	// "0102" -> bytes 0x01,0x02
	private static final char[] BUF = new char[]{0x01, 0x02, 0x03, 0x04};

	@Test
	void getParamInt_normalRead()
	{
		// read 2 bytes from offset 0 -> 0x0102
		assertEquals(0x0102, ProtoHeader.getParamInt(0, 2, BUF).intValue());
		// read 4 bytes -> 0x01020304
		assertEquals(0x01020304, ProtoHeader.getParamInt(0, 4, BUF).intValue());
		// read 1 byte from offset 3 -> 0x04
		assertEquals(0x04, ProtoHeader.getParamInt(3, 1, BUF).intValue());
	}

	@Test
	void getParamInt_lenZeroTakesRest()
	{
		// len 0 -> from offset 2 to end -> 0x0304
		assertEquals(0x0304, ProtoHeader.getParamInt(2, 0, BUF).intValue());
	}

	@Test
	void getParamInt_readPastEndThrows()
	{
		// 4 bytes from offset 2 would read indices 2..5 (length 4) -> out of range
		assertThrows(IndexOutOfBoundsException.class,
			() -> ProtoHeader.getParamInt(2, 4, BUF));
	}

	@Test
	void getParamInt_startBeyondBufferThrows()
	{
		assertThrows(IndexOutOfBoundsException.class,
			() -> ProtoHeader.getParamInt(10, 1, BUF));
	}
}
