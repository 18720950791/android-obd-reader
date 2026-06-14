/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands;

import static org.powermock.api.easymock.PowerMock.createMock;
import static org.powermock.api.easymock.PowerMock.expectLastCall;
import static org.powermock.api.easymock.PowerMock.replayAll;
import static org.powermock.api.easymock.PowerMock.verifyAll;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import eu.lighthouselabs.obd.commands.control.TroubleCodesObdCommand;

/**
 * Tests for TroubleCodesObdCommand class.
 *
 * Verifies correct DTC parsing from OBD-II Mode 03 responses including:
 * - Single and multiple trouble codes
 * - All four prefix types (P/C/B/U)
 * - Hex bytes with values > 127 (unsigned handling)
 * - Padding bytes (0000) are ignored
 * - NO DATA response handling
 * - formatResult() / getFormattedResult() consistency
 */
@PrepareForTest(InputStream.class)
public class TroubleCodesObdCommandTest {

	private TroubleCodesObdCommand command;
	private InputStream mockIn;

	@BeforeMethod
	public void setUp() throws Exception {
		command = new TroubleCodesObdCommand(0);
	}

	/**
	 * Helper: feed an ELM327 response string into the mock InputStream.
	 * The string should NOT include spaces (readResult strips them),
	 * but we include spaces here to match real ELM output format.
	 * The trailing '>' terminates readResult.
	 */
	private void feedResponse(String response) throws IOException {
		mockIn = createMock(InputStream.class);
		mockIn.read();
		for (int i = 0; i < response.length(); i++) {
			expectLastCall().andReturn((byte) response.charAt(i));
		}
		replayAll();
		command.readResult(mockIn);
	}

	/**
	 * Test: single DTC — P0134 (bytes 01 34).
	 * Response: "43 01 34>"
	 * buffer after readResult: [0x43, 0x01, 0x34]
	 */
	@Test
	public void testSingleDtc() throws IOException {
		feedResponse("43 01 34>");

		String result = command.formatResult();

		assertNotNull(result);
		assertEquals(result.trim(), "P0134");
		verifyAll();
	}

	/**
	 * Test: three DTCs — P0134, C0234, B0123.
	 *
	 * P0134 → prefix=00(P), d2=0, d3=1, d45=0x34 → bytes 01 34
	 * C0234 → prefix=01(C), d2=0, d3=2, d45=0x34 → val=0x4234 → bytes 42 34
	 * B0123 → prefix=10(B), d2=0, d3=1, d45=0x23 → val=0x8123 → bytes 81 23
	 *
	 * Response: "43 01 34 42 34 81 23>"
	 */
	@Test
	public void testMultipleDtcs() throws IOException {
		feedResponse("43 01 34 42 34 81 23>");

		String result = command.formatResult();

		assertNotNull(result);
		String[] lines = result.trim().split("\n");
		assertEquals(lines.length, 3);
		assertEquals(lines[0].trim(), "P0134");
		assertEquals(lines[1].trim(), "C0234");
		assertEquals(lines[2].trim(), "B0123");
		verifyAll();
	}

	/**
	 * Test: U-prefix DTC — U0100.
	 *
	 * U0100 → prefix=11(U), d2=0, d3=1, d45=0x00 → val=0xC100 → bytes C1 00
	 *
	 * This verifies bytes > 127 (0xC1 = 193) are handled as unsigned.
	 * Response: "43 C1 00>"
	 */
	@Test
	public void testUPrefixAndHighByteValues() throws IOException {
		feedResponse("43 C1 00>");

		String result = command.formatResult();

		assertNotNull(result);
		assertEquals(result.trim(), "U0100");
		verifyAll();
	}

	/**
	 * Test: common powertrain code P0301 (Cylinder 1 Misfire).
	 *
	 * P0301 → prefix=00(P), d2=0, d3=3, d45=0x01 → val=0x0301 → bytes 03 01
	 * Response: "43 03 01>"
	 */
	@Test
	public void testP0301Misfire() throws IOException {
		feedResponse("43 03 01>");

		String result = command.formatResult();

		assertNotNull(result);
		assertEquals(result.trim(), "P0301");
		verifyAll();
	}

	/**
	 * Test: DTC followed by 0000 padding — padding must be ignored.
	 *
	 * P0134 followed by 00 00 padding.
	 * Response: "43 01 34 00 00>"
	 */
	@Test
	public void testPaddingIgnored() throws IOException {
		feedResponse("43 01 34 00 00>");

		String result = command.formatResult();

		assertNotNull(result);
		String[] lines = result.trim().split("\n");
		assertEquals(lines.length, 1);
		assertEquals(lines[0].trim(), "P0134");
		verifyAll();
	}

	/**
	 * Test: header-only response (no DTC data).
	 * Response: "43>"
	 */
	@Test
	public void testNoDtcInResponse() throws IOException {
		feedResponse("43>");

		String result = command.formatResult();

		assertNotNull(result);
		assertEquals(result.trim(), "");
		verifyAll();
	}

	/**
	 * Test: NO DATA response.
	 * When getResult() returns "NODATA", formatResult should return empty.
	 */
	@Test
	public void testNoData() {
		// Directly set rawData to simulate NO DATA (bypassing readResult
		// since the base class hex parser would fail on non-hex chars).
		command.rawData = "NODATA";
		command.buffer.clear();

		String result = command.formatResult();

		assertNotNull(result);
		assertEquals(result, "");
	}

	/**
	 * Test: getFormattedResult() returns the same as formatResult().
	 */
	@Test
	public void testGetFormattedResultConsistency() throws IOException {
		feedResponse("43 01 34 42 34>");

		String formatted = command.getFormattedResult();
		// Note: getFormattedResult calls formatResult which resets codes buffer,
		// so we call it once and compare the expected output.
		assertNotNull(formatted);
		String[] lines = formatted.trim().split("\n");
		assertEquals(lines.length, 2);
		assertEquals(lines[0].trim(), "P0134");
		assertEquals(lines[1].trim(), "C0234");

		verifyAll();
	}

	/**
	 * Test: howManyTroubleCodes limit is respected.
	 * Buffer contains 3 DTCs, but we only want 2.
	 */
	@Test
	public void testCodeLimitByCount() throws IOException {
		// Create a command that expects only 2 codes
		command = new TroubleCodesObdCommand(2);
		feedResponse("43 01 34 42 34 81 23>");

		String result = command.formatResult();

		assertNotNull(result);
		String[] lines = result.trim().split("\n");
		assertEquals(lines.length, 2);
		assertEquals(lines[0].trim(), "P0134");
		assertEquals(lines[1].trim(), "C0234");
		verifyAll();
	}

	@AfterClass
	public void tearDown() {
		command = null;
		mockIn = null;
	}
}
