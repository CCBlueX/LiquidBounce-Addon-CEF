/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2026 CCBlueX
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.cef.browser;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.misc.EventFlags;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class CefKeyboardFallbackHandler extends CefKeyboardHandlerAdapter {
    private final boolean macOS;

    CefKeyboardFallbackHandler(boolean macOS) {
        this.macOS = macOS;
    }

    @Override
    public boolean onKeyEvent(@Nullable CefBrowser browser, CefKeyEvent event) {
        // This callback runs after the page handles input. CEF otherwise forwards unhandled
        // synthetic key-down events to the macOS menu, where plain F can toggle fullscreen.
        // Leave explicit shortcuts and Minecraft's own fullscreen binding alone.
        return macOS
                && event.type != CefKeyEvent.EventType.KEYEVENT_KEYUP
                && (event.unmodified_character == 'f' || event.unmodified_character == 'F')
                && (event.modifiers & (EventFlags.EVENTFLAG_CONTROL_DOWN
                    | EventFlags.EVENTFLAG_ALT_DOWN | EventFlags.EVENTFLAG_COMMAND_DOWN)) == 0;
    }
}
