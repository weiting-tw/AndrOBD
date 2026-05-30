package com.fr3ts0n.ecu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests aggregated warning for CSV rows referencing unknown FORMULA keys.
 *
 * Previously loadFromStream logged one warning per offending row. Now the
 * missing FORMULA keys are de-duplicated and reported in a single aggregated
 * warning, while the PID rows are still built (no exception, behaviour kept).
 *
 * CSV field order (tab separated), see EcuDataItems.FLD:
 *   SVC PID OFS LEN BIT_OFS BIT_LEN BIT_MASK FORMULA FORMAT MIN MAX UPDATE_MIN MNEMONIC LABEL DESCRIPTION
 */
class EcuDataItemsCsvWarnTest
{
	private final Logger log = Logger.getLogger("data.items");
	private final List<LogRecord> warnings = new ArrayList<>();
	private Handler captureHandler;

	@BeforeEach
	void attachHandler()
	{
		warnings.clear();
		captureHandler = new Handler()
		{
			@Override public void publish(LogRecord record)
			{
				if (record.getLevel() == Level.WARNING) warnings.add(record);
			}
			@Override public void flush() {}
			@Override public void close() {}
		};
		log.addHandler(captureHandler);
	}

	@AfterEach
	void detachHandler()
	{
		log.removeHandler(captureHandler);
	}

	private static ByteArrayInputStream csv(String body)
	{
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void missingFormulas_aggregatedIntoSingleWarning()
	{
		EcuDataItems items = new EcuDataItems();
		// constructor loads the real CSVs (which may legitimately warn); reset
		warnings.clear();

		// header (skipped) + 3 rows: 2 unique bogus formula keys (A twice, B once).
		// Unique test_* mnemonics so the static byMnemonic map isn't polluted.
		String header = "svc\tpid\tofs\tlen\tbofs\tblen\tmask\tformula\tfmt\tmin\tmax\tupd\tmnemonic\tlabel\tdesc";
		String rowA1 = "0x01\t0x91\t0\t1\t0\t8\t0xFF\tTEST_BOGUS_A\t%.0f\t\t\t0\ttest_csvwarn_a1\tA1\tdesc";
		String rowA2 = "0x01\t0x92\t0\t1\t0\t8\t0xFF\tTEST_BOGUS_A\t%.0f\t\t\t0\ttest_csvwarn_a2\tA2\tdesc";
		String rowB1 = "0x01\t0x93\t0\t1\t0\t8\t0xFF\tTEST_BOGUS_B\t%.0f\t\t\t0\ttest_csvwarn_b1\tB1\tdesc";
		String body = String.join("\n", header, rowA1, rowA2, rowB1) + "\n";

		items.loadFromStream(csv(body));

		// exactly ONE aggregated warning (not one per row)
		assertEquals(1, warnings.size(), "should emit a single aggregated warning");
		String msg = warnings.get(0).getMessage();
		assertTrue(msg.contains("2 formula key"), "should report 2 unique missing keys: " + msg);
		assertTrue(msg.contains("TEST_BOGUS_A"), "should name TEST_BOGUS_A: " + msg);
		assertTrue(msg.contains("TEST_BOGUS_B"), "should name TEST_BOGUS_B: " + msg);
	}

	@Test
	void allFormulasResolved_noWarning()
	{
		EcuDataItems items = new EcuDataItems();
		warnings.clear();

		// ONETOONE is a real conversion key present in conversions.csv
		String header = "svc\tpid\tofs\tlen\tbofs\tblen\tmask\tformula\tfmt\tmin\tmax\tupd\tmnemonic\tlabel\tdesc";
		String row = "0x01\t0x94\t0\t1\t0\t8\t0xFF\tONETOONE\t%.0f\t\t\t0\ttest_csvwarn_ok\tOK\tdesc";
		String body = String.join("\n", header, row) + "\n";

		items.loadFromStream(csv(body));

		assertEquals(0, warnings.size(), "no warning when all FORMULA keys resolve");
	}
}
