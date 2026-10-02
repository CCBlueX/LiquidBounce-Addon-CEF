/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
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

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.ccbluex.liquidbounce.cef.CefRuntime;
import net.ccbluex.liquidbounce.cef.CefPlatform;
import net.ccbluex.liquidbounce.cef.cursor.CefCursorHelper;
import net.ccbluex.liquidbounce.cef.listeners.CefCursorChangeListener;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.misc.CefCursorType;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.UUID;

import static net.ccbluex.liquidbounce.cef.CefRuntime.mc;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class CefOffscreenBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final CefTextureRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final CefDragContext dragContext = new CefDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private CefCursorChangeListener cursorChangeListener;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend CefOffscreenBrowser and override the repaint logic
    protected ByteBuffer popupGraphics;
    protected Rectangle popupSize;
    protected boolean showPopup = false;
    protected boolean popupDrawn = false;
    private long lastClickTime = 0;
    private int clicks;
    private int mouseButton;

    private final boolean isMacOs = CefPlatform.getPlatform().isMacOS();
    private final boolean isWindows = CefPlatform.getPlatform().isWindows();
    private final boolean isWayland = "wayland".equals(SDLVideo.SDL_GetCurrentVideoDriver());

    /**
     * Sent in front of the copied text, so that only the copy script this browser just ran can write the clipboard.
     */
    private @Nullable String clipboardToken;

    /**
     * Hands what a copy or cut would put on the clipboard to the {@code cefClipboard} query.
     */
    private static final String COPY_SCRIPT = """
            ((cut, token) => {
                const element = document.activeElement;
                const field = element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement
                    ? element : null;
                if (field?.type === 'password') {
                    return;
                }

                const data = new DataTransfer();
                const event = new ClipboardEvent(cut ? 'cut' : 'copy',
                    {clipboardData: data, bubbles: true, cancelable: true});
                let text;
                if (!element.dispatchEvent(event)) {
                    text = data.getData('text/plain');
                } else {
                    text = field && field.selectionStart !== null
                        ? field.value.slice(field.selectionStart, field.selectionEnd)
                        : getSelection().toString();
                    if (cut && text && (field || element.isContentEditable)) {
                        document.execCommand('delete');
                    }
                }

                if (text) {
                    cefClipboard({request: token + text});
                }
            })(%s, %s)""";

    private static final String PASTE_SCRIPT = """
            (text => {
                const data = new DataTransfer();
                data.setData('text/plain', text);
                const event = new ClipboardEvent('paste', {clipboardData: data, bubbles: true, cancelable: true});
                if (document.activeElement.dispatchEvent(event)) {
                    document.execCommand('insertText', false, text);
                }
            })(%s)""";

    public CefOffscreenBrowser(CefClientWrapper client, String url, boolean transparent, CefOffscreenBrowserSettings browserSettings) {
        this(client, url, transparent, browserSettings, null);
    }

    /**
     * @param requestContext The request context to load the browser in, or null for the global one.
     *                       A context created with {@link CefRequestContext#createContext} keeps its
     *                       cookies and storage in memory, which is how a private browsing session is
     *                       made.
     */
    public CefOffscreenBrowser(CefClientWrapper client, String url, boolean transparent, CefOffscreenBrowserSettings browserSettings,
                       @Nullable CefRequestContext requestContext) {
        super(client.getHandle(), url, transparent, requestContext, browserSettings);
        renderer = new CefTextureRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(CefCursorType.fromId(cefCursorID));

        mc.schedule(renderer::initialize);
    }

    public CefTextureRenderer getRenderer() {
        return renderer;
    }

    /**
     * Convenience method to get the ResourceLocation for this browser's texture.
     * This can be used directly with GuiGraphics rendering methods.
     *
     * @return The ResourceLocation for this browser's texture, or null if not initialized
     */
    public Identifier getTextureLocation() {
        return renderer != null ? renderer.getIdentifier() : null;
    }

    /**
     * Check if the browser's texture is ready for rendering.
     *
     * @return true if the texture is initialized and ready to be rendered
     */
    public boolean isTextureReady() {
        return renderer != null && renderer.isTextureReady();
    }

    public CefCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(CefCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public CefDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        showPopup = show;
        if (!show) popupDrawn = false;
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        popupSize = size;
        this.popupGraphics = ByteBuffer.allocateDirect(
                size.width * size.height * 4
        );
    }

    // Graphics
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        // nothing to update
        if (dirtyRects.length == 0) {
            return;
        }
        
        if (!popup) {
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                // upload full texture
                // this also sets up the texture size and creates the texture
                renderer.onPaint(buffer, width, height);
            } else {
                if (!renderer.isTextureReady()) return;
                renderer.onPaint(buffer, width, height, dirtyRects, 0, 0);
                if ((popupDrawn || showPopup) && popupSize != null) {
                    // interpret where the popup was as a dirty rect
                    if (!showPopup) {
                        // if the popup is not visible, just draw the contents of the buffer
                        renderer.onPaint(buffer, width, height,
                                popupSize.width, popupSize.height,
                                popupSize.x, popupSize.y,
                                popupSize.width, popupSize.height);
                        popupGraphics = null;
                        popupSize = null;
                    } else if (popupDrawn) {
                        // else, a use copy of the popup graphics, as it needs to remain visible
                        // and for some reason that I do not for the life of me understand, chromium does not seem to keep this data in memory outside of the paint loop, meaning it has to be copied around, which wastes performance
                        renderer.onPaint(popupGraphics, popupSize.width, popupSize.height,
                                0, 0,
                                popupSize.x, popupSize.y,
                                popupSize.width, popupSize.height);
                    }
                }
            }
        } else {
            if (!renderer.isTextureReady() || popupSize == null) return;
            int start = buffer.capacity();
            int end = 0;
            for (Rectangle dirtyRect : dirtyRects) {
                renderer.onPaint(buffer, popupSize.width, popupSize.height,
                        dirtyRect.x, dirtyRect.y,
                        popupSize.x + dirtyRect.x, popupSize.y + dirtyRect.y,
                        dirtyRect.width, dirtyRect.height);

                int rectStart = (dirtyRect.x + ((dirtyRect.y) * popupSize.width)) << 2;
                if (rectStart < start) start = rectStart;

                int rectEnd = (((dirtyRect.y + dirtyRect.height - 1) * popupSize.width)
                        + dirtyRect.x + dirtyRect.width) << 2;
                if (rectEnd > end) end = rectEnd;
            }
            if (start < 0) start = 0;
            if (end > buffer.capacity()) end = buffer.capacity();

            if (end > start) {
                // TODO: check if it's more performant to go for row-wise copies or if it's better to just copy the updated region
                if (this.popupGraphics != null) {
                    long addrFrom = MemoryUtil.memAddress(buffer);
                    long addrTo = MemoryUtil.memAddress(popupGraphics);
                    MemoryUtil.memCopy(
                            addrFrom + start,
                            addrTo + start,
                            (end - start)
                    );
                }
            }

            popupDrawn = true;
        }
        super.onPaint(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public void onAcceleratedPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                                   CefAcceleratedPaintInfo info) {
        // nothing to update
        if (dirtyRects.length == 0) {
            return;
        }

        // refuse 1x1 rectangles
        if (info.width <= 1 || info.height <= 1 ||
                dirtyRects[0].width <= 1 || dirtyRects[0].height <= 1) {
            return;
        }

        var width = info.width;
        var height = info.height;

        if (lastWidth != width || lastHeight != height) {
            var rect = dirtyRects[0];

            if (rect.width == width && rect.height == height && rect.x == 0 && rect.y == 0) {
                lastWidth = width;
                lastHeight = height;
            } else {
                // Likely an outdated paint-call
                return;
            }
        }

        if (!popup) {
            renderer.onAcceleratedPaint(info, width, height);
        } else {
            CefRuntime.INSTANCE.LOGGER.warn("Accelerated paint for popups is not supported in CefRuntime.");
        }

        super.onAcceleratedPaint(browser, popup, dirtyRects, info);
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs

    /**
     * Forwards a key press. The parameters mirror {@code net.minecraft.client.input.KeyEvent}.
     *
     * @param scancode  the SDL scancode of the physical key ({@code KeyEvent.key()},
     *                  comparable against {@code InputConstants.KEY_*})
     * @param keycode   the layout dependent SDL keycode ({@code KeyEvent.keycode()},
     *                  comparable against {@code InputConstants.KEYCODE_*})
     * @param modifiers the SDL keymod bitmask ({@code KeyEvent.modifiers()})
     */
    public void sendKeyPress(int scancode, int keycode, int modifiers) {
        if (isControlOrCommand(modifiers) && scancode == InputConstants.KEY_R) {
            reload();
            return;
        }

        if (handleClipboardShortcut(new KeyEvent(scancode, keycode, modifiers))) {
            return;
        }

        sendKeyEvent(createKeyEvent(CefKeyEvent.KEY_PRESS, scancode, keycode, modifiers));
    }

    private boolean handleClipboardShortcut(KeyEvent event) {
        // CEF does not map the Cmd shortcuts on macOS
        if (InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY) {
            if (event.isCopy()) {
                getFocusedFrame().copy();
            } else if (event.isPaste()) {
                getFocusedFrame().paste();
            } else if (event.isCut()) {
                getFocusedFrame().cut();
            } else if (event.isSelectAll()) {
                getFocusedFrame().selectAll();
            } else {
                return false;
            }
            return true;
        }

        // Chromium runs on X11, and KWin only hands the Wayland clipboard to X11 clients while one of
        // their windows is focused. Copies go through SDL as well: reading Chromium's own selection back
        // would block this thread, which Chromium answers on, until SDL gives up after seconds.
        if (isWayland) {
            if (event.isCopy() || event.isCut()) {
                clipboardToken = UUID.randomUUID().toString();
                executeInFocusedFrame(COPY_SCRIPT.formatted(event.isCut(), new JsonPrimitive(clipboardToken)));
                return true;
            }

            if (event.isPaste()) {
                var text = SDLClipboard.SDL_HasClipboardText() ? SDLClipboard.SDL_GetClipboardText() : null;
                if (text != null && !text.isEmpty()) {
                    executeInFocusedFrame(PASTE_SCRIPT.formatted(new JsonPrimitive(text)));
                }
                return true;
            }
        }

        return false;
    }

    boolean writeClipboard(String request) {
        var token = clipboardToken;
        if (token == null || !request.startsWith(token)) {
            return false;
        }

        clipboardToken = null;
        SDLClipboard.SDL_SetClipboardText(request.substring(token.length()));
        return true;
    }

    private void executeInFocusedFrame(String script) {
        var frame = getFocusedFrame();
        frame.executeJavaScript(script, frame.getURL(), 0);
    }

    /**
     * @see #sendKeyPress(int, int, int)
     */
    public void sendKeyRelease(int scancode, int keycode, int modifiers) {
        if (isControlOrCommand(modifiers) && scancode == InputConstants.KEY_R) {
            return;
        }

        sendKeyEvent(createKeyEvent(CefKeyEvent.KEY_RELEASE, scancode, keycode, modifiers));
    }

    /**
     * The native side identifies keys by SDL keycode. For a printable key the
     * keycode is its code point, which doubles as the key char; every other
     * keycode carries a mask bit and has no character representation.
     */
    private static CefKeyEvent createKeyEvent(int id, int scancode, int keycode, int modifiers) {
        char keyChar = (keycode & (SDLKeycode.SDLK_SCANCODE_MASK | SDLKeycode.SDLK_EXTENDED_MASK)) == 0
                ? (char) keycode
                : '\0';

        var event = new CefKeyEvent(id, keycode, keyChar, modifiers);
        event.scancode = scancode;
        return event;
    }

    public void sendKeyTyped(int codepoint) {
        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, codepoint, (char) codepoint, 0);
        sendKeyEvent(e);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, clicks, mouseButton,
                dragContext.getVirtualModifiers(btnMask)));

        if (dragContext.isDragging()) {
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
        }
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            btnMask |= CefMouseEvent.BUTTON1_MASK;
        } else if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            btnMask |= CefMouseEvent.BUTTON2_MASK;
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            btnMask |= CefMouseEvent.BUTTON3_MASK;
        }

        // Double click handling
        var time = System.currentTimeMillis();
        clicks = time - lastClickTime < 500 ? 2 : 1;

        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_PRESSED, mouseX, mouseY, clicks, button, btnMask));

        this.lastClickTime = time;
        this.mouseButton = button;
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON1_MASK;
        } else if (button == InputConstants.MOUSE_BUTTON_MIDDLE && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON2_MASK;
        } else if (button == InputConstants.MOUSE_BUTTON_RIGHT && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON3_MASK;
        }

        // drag & drop
        if (dragContext.isDragging()) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                finishDragging(mouseX, mouseY);
            }
        }

        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_RELEASED, mouseX, mouseY, clicks, button, btnMask));
        this.mouseButton = 0;
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount) {
        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!isMacOs) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        var event = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, 0);
        sendMouseWheelEvent(event);
    }

    public void clear() {
        invalidate();
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off-screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) {
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));
        }

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) {
        // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    public void close() {
        renderer.close();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        mc.schedule(renderer::close);
        super.finalize();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        // We do not want to change the cursor state since Minecraft does this for us.
        if (cursorType == CefCursorType.NONE) return;

        CefCursorHelper.getCursorType(cursorType).select();
    }

    private boolean isControlOrCommand(int modifiers) {
        int modifier = InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY
                ? InputConstants.MOD_SUPER
                : InputConstants.MOD_CONTROL;
        return (modifiers & modifier) != 0;
    }

}
