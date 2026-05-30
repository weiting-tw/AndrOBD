package com.fr3ts0n.ecu.prot.obd;

import org.junit.jupiter.api.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for negative-response-code (NRC) handling in ObdProt.handleTelegram().
 *
 * NRC telegram layout (hex chars), parsed via NR_PARAMETERS:
 *   ID_NR_ID   = offset 0 (0x7F marks a negative response)
 *   ID_NR_SVC  = offset 2
 *   ID_NR_CODE = offset 4
 *
 * The NRC enum only lists the standard codes, so a vehicle returning any other
 * code made NRC.get() return null and the old code NPE'd at nrc.toString(). The
 * surrounding try/catch swallowed that NPE — so the PROP_NRC notification never
 * fired and no reaction ran. The fix handles the unknown code gracefully and
 * still notifies listeners.
 */
class ObdProtNrcTest
{
	private PropertyChangeEvent lastNrcEvent;

	private PropertyChangeListener nrcListener()
	{
		return new PropertyChangeListener()
		{
			@Override
			public void propertyChange(PropertyChangeEvent evt)
			{
				if (ObdProt.PROP_NRC.equals(evt.getPropertyName()))
				{
					lastNrcEvent = evt;
				}
			}
		};
	}

	@Test
	void handleTelegram_unknownNrcCode_stillNotifies()
	{
		ObdProt prot = new ObdProt();
		lastNrcEvent = null;
		prot.addPropertyChangeListener(nrcListener());

		// service 0x09, NRC code 0xFF -> not in the enum (unknown)
		prot.handleTelegram("7F09FF>".toCharArray());

		// Fix: unknown NRC must still fire a PROP_NRC notification (old code NPE'd
		// before firePropertyChange, so the event was lost in the catch block).
		assertNotNull(lastNrcEvent, "unknown NRC should still fire a PROP_NRC event");
		assertTrue(String.valueOf(lastNrcEvent.getNewValue()).contains("FF"),
			"NRC message should mention the unknown code 0xFF");
	}

	@Test
	void handleTelegram_knownNrcCode_notifies()
	{
		ObdProt prot = new ObdProt();
		lastNrcEvent = null;
		prot.addPropertyChangeListener(nrcListener());

		// service 0x09, NRC code 0x11 (SNS - Service not supported), a known code
		prot.handleTelegram("7F0911>".toCharArray());

		assertNotNull(lastNrcEvent, "known NRC should fire a PROP_NRC event");
	}
}
