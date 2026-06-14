/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands.control;

import java.io.IOException;
import java.io.InputStream;

import eu.lighthouselabs.obd.commands.ObdCommand;
import eu.lighthouselabs.obd.enums.AvailableCommandNames;

/**
 * In order to get ECU Trouble Codes, one must first send a DtcNumberObdCommand
 * and by so, determining the number of error codes available by means of
 * getTotalAvailableCodes().
 * 
 * If none are available (totalCodes < 1), don't instantiate this command.
 */
public class TroubleCodesObdCommand extends ObdCommand {

	protected final static char[] dtcLetters = { 'P', 'C', 'B', 'U' };
	protected final static char[] hexArray = "0123456789ABCDEF".toCharArray();

	private StringBuffer codes = null;
	private int howManyTroubleCodes = 0;

	/**
	 * Default ctor.
	 */
	public TroubleCodesObdCommand(int howManyTroubleCodes) {
		super("03");

		codes = new StringBuffer();
		this.howManyTroubleCodes = howManyTroubleCodes;
	}

	/**
	 * Copy ctor.
	 * 
	 * @param other
	 */
	public TroubleCodesObdCommand(TroubleCodesObdCommand other) {
		super(other);
		codes = new StringBuffer();
	}

	/**
	 * Reads the Mode 03 response.
	 *
	 * <p>
	 * The base implementation eagerly decodes every two characters of the
	 * response into the integer buffer, which throws on the multi-line frames
	 * (separated by carriage returns) and the non-hexadecimal payloads such as
	 * "NO DATA" / "SEARCHING" that this command legitimately receives. Here we
	 * keep the raw response intact (so {@link #getResult()} can still recognise
	 * "NO DATA") and fill the buffer best-effort, ignoring anything that is not
	 * a valid hexadecimal byte. The actual trouble codes are decoded later in
	 * {@link #formatResult()}.
	 * </p>
	 */
	@Override
	public void readResult(InputStream in) throws IOException {
		byte b = 0;
		StringBuilder res = new StringBuilder();

		// read until '>' arrives
		while ((char) (b = (byte) in.read()) != '>')
			if ((char) b != ' ')
				res.append((char) b);

		rawData = res.toString().trim();

		// Fill the buffer best-effort with the response bytes, skipping line
		// separators and any non-hexadecimal characters so we never throw here.
		buffer.clear();
		String hex = rawData.replaceAll("[^0-9A-Fa-f]", "");
		for (int i = 0; i + 2 <= hex.length(); i += 2) {
			buffer.add(Integer.parseInt(hex.substring(i, i + 2), 16));
		}
	}

	/**
	 * @return the formatted result of this command in string representation.
	 */
	public String formatResult() {
		// Re-build the result on every call so that formatResult() and
		// getFormattedResult() always agree, even when invoked repeatedly.
		codes.setLength(0);

		String result = getResult();
		if (result == null || result.isEmpty() || "NODATA".equals(result)) {
			return codes.toString();
		}

		// A single response may contain several frames separated by carriage
		// returns and/or line feeds. Decode each one independently.
		for (String line : result.split("[\\r\\n]+")) {
			String workingData = line.replaceAll("\\s", "").toUpperCase();
			if (workingData.isEmpty()) {
				continue;
			}

			// Skip the Mode 03 positive response header (0x43) when present.
			if (workingData.startsWith("43")) {
				workingData = workingData.substring(2);
			}

			try {
				// Two bytes (four hex chars) per trouble code; stop on a
				// truncated tail instead of reading out of bounds.
				for (int i = 0; i + 4 <= workingData.length(); i += 4) {
					int b1 = Integer.parseInt(workingData.substring(i, i + 2), 16);
					int b2 = Integer.parseInt(workingData.substring(i + 2, i + 4), 16);

					// 0x0000 is padding, not an actual trouble code.
					if (b1 == 0 && b2 == 0) {
						continue;
					}

					// First two bits -> category, next two bits -> first digit
					// (0-3), remaining twelve bits -> three hexadecimal digits.
					codes.append(dtcLetters[(b1 >> 6) & 0x03]);
					codes.append(hexArray[(b1 >> 4) & 0x03]);
					codes.append(hexArray[b1 & 0x0F]);
					codes.append(hexArray[(b2 >> 4) & 0x0F]);
					codes.append(hexArray[b2 & 0x0F]);
					codes.append('\n');
				}
			} catch (NumberFormatException e) {
				// Non-hexadecimal (illegal) data on this line: skip it.
			}
		}

		return codes.toString();
	}

	@Override
	public String getFormattedResult() {
		return formatResult();
	}

	@Override
	public String getName() {
		return AvailableCommandNames.TROUBLE_CODES.getValue();
	}
}
