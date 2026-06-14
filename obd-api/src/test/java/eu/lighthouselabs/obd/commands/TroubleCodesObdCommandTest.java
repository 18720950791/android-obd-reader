/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.testng.annotations.Test;

import eu.lighthouselabs.obd.commands.control.TroubleCodesObdCommand;

/**
 * Protocol tests for {@link TroubleCodesObdCommand} (OBD-II Mode 03 stored DTCs).
 *
 * <p>
 * Each test feeds a raw ELM327-style response (terminated by '>') through the
 * real {@code readResult} pipeline and verifies the decoded trouble codes.
 * </p>
 */
public class TroubleCodesObdCommandTest {

	/**
	 * Helper: run the full read + decode pipeline for a raw response.
	 *
	 * @param response a raw ELM327 response, which MUST end with '>'.
	 * @return the formatted (decoded) result.
	 */
	private static String decode(String response) throws IOException {
		TroubleCodesObdCommand command = new TroubleCodesObdCommand(0);
		command.readResult(new ByteArrayInputStream(response.getBytes()));
		return command.getFormattedResult();
	}

	/**
	 * A single powertrain code is decoded after skipping the 0x43 header and the
	 * trailing 0x0000 padding.
	 */
	@Test
	public void testSingleDtc() throws IOException {
		assertEquals(decode("43 01 33 00 00 00 00>"), "P0133\n");
	}

	/**
	 * All four categories (P/C/B/U) are produced correctly. The chassis code
	 * here is encoded as bytes 0x43 0x00 (C0300), which also proves that only
	 * the leading header byte - and not a 0x43 that is part of a real code - is
	 * stripped.
	 */
	@Test
	public void testAllFourPrefixes() throws IOException {
		// header 43 | 01 33 -> P0133 | 43 00 -> C0300 | 80 01 -> B0001
		//           | C1 00 -> U0100 | 00 00 -> padding
		String response = "43 01 33 43 00 80 01 C1 00 00 00>";
		assertEquals(decode(response), "P0133\nC0300\nB0001\nU0100\n");
	}

	/**
	 * Hexadecimal nibbles A-F (which broke the old {@code Byte.parseByte} based
	 * parser) are decoded correctly.
	 */
	@Test
	public void testHexNibblesAreParsed() throws IOException {
		// AB CD -> category 10 (B), digit1 (0xAB>>4)&3 = 2, digit2 0xB,
		// digit3 0xC, digit4 0xD -> B2BCD
		assertEquals(decode("43 AB CD 00 00 00 00>"), "B2BCD\n");
	}

	/**
	 * Several real codes in one frame, in order, ignoring the padding.
	 */
	@Test
	public void testMultipleDtcsSingleFrame() throws IOException {
		assertEquals(decode("43 01 33 C1 00 00 00>"), "P0133\nU0100\n");
	}

	/**
	 * A response made up only of padding yields no codes.
	 */
	@Test
	public void testOnlyPadding() throws IOException {
		assertEquals(decode("43 00 00 00 00 00 00>"), "");
	}

	/**
	 * Multi-frame (multi-line) responses are decoded line by line.
	 */
	@Test
	public void testMultipleResponseLines() throws IOException {
		String response = "43 01 33 00 00 00 00\r43 80 01 00 00 00 00\r>";
		assertEquals(decode(response), "P0133\nB0001\n");
	}

	/**
	 * "NO DATA" is handled gracefully and yields an empty result.
	 */
	@Test
	public void testNoData() throws IOException {
		assertEquals(decode("NO DATA>"), "");
	}

	/**
	 * A "SEARCHING..." response is treated as no data.
	 */
	@Test
	public void testSearching() throws IOException {
		assertEquals(decode("SEARCHING...>"), "");
	}

	/**
	 * A blank response (only carriage returns / line feeds) does not crash.
	 */
	@Test
	public void testBlankResponse() throws IOException {
		assertEquals(decode("\r\r>"), "");
	}

	/**
	 * A truncated response (incomplete trailing byte pair) is ignored without
	 * an out-of-bounds error.
	 */
	@Test
	public void testTruncatedResponse() throws IOException {
		assertEquals(decode("43 01>"), "");
		assertEquals(decode("43 01 3>"), "");
	}

	/**
	 * A response containing illegal (non-hexadecimal) data does not crash; the
	 * offending line is skipped.
	 */
	@Test
	public void testIllegalResponse() throws IOException {
		assertEquals(decode("43 ZZ 33 00 00 00 00>"), "");
	}

	/**
	 * {@code formatResult()} and {@code getFormattedResult()} return the same
	 * value, and repeated invocations are idempotent (no double appending).
	 */
	@Test
	public void testFormattedResultConsistency() throws IOException {
		TroubleCodesObdCommand command = new TroubleCodesObdCommand(0);
		command.readResult(new ByteArrayInputStream("43 01 33 00 00 00 00>".getBytes()));

		assertEquals(command.formatResult(), "P0133\n");
		assertEquals(command.getFormattedResult(), command.formatResult());
		// Calling again must not append the codes a second time.
		assertEquals(command.formatResult(), "P0133\n");
	}
}
