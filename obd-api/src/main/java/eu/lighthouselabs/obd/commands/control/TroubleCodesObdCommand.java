/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands.control;

import eu.lighthouselabs.obd.commands.ObdCommand;
import eu.lighthouselabs.obd.enums.AvailableCommandNames;

/**
 * In order to get ECU Trouble Codes, one must first send a DtcNumberObdCommand
 * and by so, determining the number of error codes available by means of
 * getTotalAvailableCodes().
 *
 * If none are available (totalCodes < 1), don't instantiate this command.
 *
 * OBD-II Mode 03 response format:
 *   43 AA BB CC DD EE FF ...
 * where 0x43 is the response header byte, and each subsequent pair of bytes
 * (AA BB, CC DD, ...) encodes one DTC per SAE J2012:
 *   - Bits 15-14: system prefix (00=P, 01=C, 10=B, 11=U)
 *   - Bits 13-12: second digit
 *   - Bits 11-8:  third digit
 *   - Bits 7-0:   fourth+fifth digits (hex)
 * A DTC value of 0x0000 is padding and should be ignored.
 */
public class TroubleCodesObdCommand extends ObdCommand {

	protected final static char[] dtcLetters = { 'P', 'C', 'B', 'U' };

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
		this.howManyTroubleCodes = other.howManyTroubleCodes;
	}

	/**
	 * Parse the response buffer and build DTC code strings.
	 *
	 * Uses the already-parsed {@code buffer} (populated by
	 * {@link ObdCommand#readResult(java.io.InputStream)}), skipping the first
	 * byte which is the Mode 03 response header (0x43).  Each subsequent pair
	 * of bytes is decoded into a standard five-character DTC code with a
	 * P/C/B/U prefix.  Padding values of 0x0000 are skipped.
	 *
	 * @return newline-separated DTC codes, or empty string on NO DATA / no codes
	 */
	public String formatResult() {
		codes.setLength(0);

		String res = getResult();
		if ("NODATA".equals(res)) {
			return codes.toString();
		}

		/*
		 * The buffer is already parsed by readResult():
		 *   buffer.get(0) = 0x43  (response header — skip)
		 *   buffer.get(1), buffer.get(2) = first DTC (2 bytes)
		 *   buffer.get(3), buffer.get(4) = second DTC
		 *   ...
		 */
		int startIndex = 1; // skip header byte 0x43
		int available = buffer.size();

		int codesFound = 0;
		int i = startIndex;
		while (i + 1 < available) {
			int b1 = buffer.get(i);
			int b2 = buffer.get(i + 1);
			i += 2;

			int val = (b1 << 8) | b2;

			// 0x0000 is padding — skip it
			if (val == 0) {
				continue;
			}

			// Decode DTC per SAE J2012
			char prefix = dtcLetters[(val >> 14) & 0x03];
			int digit2 = (val >> 12) & 0x03;
			int digit3 = (val >> 8) & 0x0F;
			int digit45 = val & 0xFF;

			codes.append(prefix);
			codes.append(digit2);
			codes.append(Integer.toHexString(digit3).toUpperCase());
			codes.append(String.format("%02X", digit45));
			codes.append("\n");

			codesFound++;

			// Stop if we've found all expected codes
			if (howManyTroubleCodes > 0 && codesFound >= howManyTroubleCodes) {
				break;
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
