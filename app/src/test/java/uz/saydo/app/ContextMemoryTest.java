package uz.saydo.app;

import org.junit.Test;

import uz.saydo.app.kernel.workflow.ContextMemory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Unit tests for ContextMemory (used by VoiceService for parameter substitution). */
public class ContextMemoryTest {

    @Test
    public void testContextMemoryRegisters() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        assertEquals("", memory.get(ContextMemory.REG_0));
        memory.set(ContextMemory.REG_0, "12345");
        assertEquals("12345", memory.get(ContextMemory.REG_0));
        assertTrue(memory.has(ContextMemory.REG_0));

        memory.set(ContextMemory.CLIPBOARD, "copied text");
        assertEquals("copied text", memory.get(ContextMemory.CLIPBOARD));
    }

    @Test
    public void testContextMemoryOtpRegexExtraction() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        String smsBody = "Your One-Time Password is 849201. Valid for 5 minutes.";
        boolean extracted = memory.extractPattern(smsBody, "\\b\\d{6}\\b", ContextMemory.LAST_OTP);

        assertTrue(extracted);
        assertEquals("849201", memory.get(ContextMemory.LAST_OTP));
        assertEquals("849201", memory.get(ContextMemory.LAST_EXTRACTED_TEXT));
    }

    @Test
    public void testContextMemoryTemplateInterpolation() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        memory.set(ContextMemory.LAST_OTP, "987654");
        memory.set(ContextMemory.REG_1, "Alice");

        String interpolated = memory.interpolate("Send code {$LAST_OTP} to {$REG_1}");
        assertEquals("Send code 987654 to Alice", interpolated);
    }
}
