package net.ccbluex.liquidbounce.cef.browser;

import org.cef.handler.CefKeyboardHandler.CefKeyEvent;
import org.cef.misc.BoolRef;
import org.cef.misc.EventFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CefKeyboardFallbackHandlerTest {

    @ParameterizedTest
    @EnumSource(value = CefKeyEvent.EventType.class,
        names = {"KEYEVENT_RAWKEYDOWN", "KEYEVENT_KEYDOWN", "KEYEVENT_CHAR"})
    void consumesTypingFOnMacAfterThePageHandlesInput(CefKeyEvent.EventType type) throws Exception {
        var handler = new CefKeyboardFallbackHandler(true);
        var event = keyEvent(type, 'f', 0);
        var shortcut = new BoolRef(false);
        assertFalse(handler.onPreKeyEvent(null, event, shortcut));
        assertFalse(shortcut.get());
        assertTrue(handler.onKeyEvent(null, event));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, EventFlags.EVENTFLAG_SHIFT_DOWN, EventFlags.EVENTFLAG_CAPS_LOCK_ON,
        EventFlags.EVENTFLAG_SHIFT_DOWN | EventFlags.EVENTFLAG_CAPS_LOCK_ON})
    void alsoConsumesUppercaseF(int modifiers) throws Exception {
        var handler = new CefKeyboardFallbackHandler(true);
        assertTrue(handler.onKeyEvent(null, keyEvent(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, 'F', modifiers)));
    }

    @ParameterizedTest
    @ValueSource(ints = {EventFlags.EVENTFLAG_CONTROL_DOWN, EventFlags.EVENTFLAG_ALT_DOWN,
        EventFlags.EVENTFLAG_COMMAND_DOWN, EventFlags.EVENTFLAG_CONTROL_DOWN | EventFlags.EVENTFLAG_COMMAND_DOWN})
    void preservesExplicitShortcuts(int modifiers) throws Exception {
        var handler = new CefKeyboardFallbackHandler(true);
        assertFalse(handler.onKeyEvent(null, keyEvent(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, 'f', modifiers)));
    }

    @ParameterizedTest
    @EnumSource(CefKeyEvent.EventType.class)
    void leavesOtherPlatformsUnchanged(CefKeyEvent.EventType type) throws Exception {
        var handler = new CefKeyboardFallbackHandler(false);
        assertFalse(handler.onKeyEvent(null, keyEvent(type, 'f', 0)));
    }

    @Test
    void leavesKeyReleaseAndOtherKeysUnchanged() throws Exception {
        var handler = new CefKeyboardFallbackHandler(true);
        assertFalse(handler.onKeyEvent(null, keyEvent(CefKeyEvent.EventType.KEYEVENT_KEYUP, 'f', 0)));
        assertFalse(handler.onKeyEvent(null, keyEvent(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, 'x', 0)));
        assertFalse(handler.onKeyEvent(null, keyEvent(CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN, '\0', 0)));
    }

    private static CefKeyEvent keyEvent(CefKeyEvent.EventType type, char character, int modifiers) throws Exception {
        // The JNI event constructor is package-private; no Chromium runtime is needed for these callbacks.
        var constructor = CefKeyEvent.class.getDeclaredConstructor(CefKeyEvent.EventType.class,
            int.class, int.class, int.class, boolean.class, char.class, char.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, modifiers, 0, 0, false, character, character, true);
    }
}
