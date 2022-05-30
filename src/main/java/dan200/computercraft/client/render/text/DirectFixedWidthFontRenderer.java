/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */
package dan200.computercraft.client.render.text;

import com.mojang.blaze3d.platform.MemoryTracker;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dan200.computercraft.client.render.RenderTypes;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.terminal.TextBuffer;
import dan200.computercraft.shared.util.Colour;
import dan200.computercraft.shared.util.Palette;
import me.jellysquid.mods.sodium.client.util.color.ColorABGR;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;

import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.*;

/**
 * An optimised copy of {@link FixedWidthFontRenderer} emitter emits directly to a {@link ByteBuffer} rather than
 * emitting to {@link VertexConsumer}. This allows us to emit vertices very quickly, when using the VBO renderer.
 *
 * There are some limitations here:
 * <ul>
 *   <li>No transformation matrix (not needed for VBOs).</li>
 *   <li>Only works with {@link DefaultVertexFormat#POSITION_COLOR_TEX_LIGHTMAP}.</li>
 *   <li>The buffer <strong>MUST</strong> be allocated with {@link MemoryTracker}, and not through any other means.</li>
 * </ul>
 *
 * Note this is almost an exact copy of {@link FixedWidthFontRenderer}. While the code duplication is unfortunate,
 * it is measurably faster than introducing polymorphism into {@link FixedWidthFontRenderer}.
 *
 * <strong>IMPORTANT: </strong> When making changes to this class, please check if you need to make the same changes to
 * {@link FixedWidthFontRenderer}.
 */
public final class DirectFixedWidthFontRenderer
{
    private DirectFixedWidthFontRenderer()
    {
    }

    private static void drawChar( IrisTextVertexSink sink, float x, float y, int index, byte[] colour )
    {
        // Short circuit to avoid the common case - the texture should be blank here after all.
        if( index == '\0' || index == ' ' ) return;

        int column = index % 16;
        int row = index / 16;

        int xStart = 1 + column * (FONT_WIDTH + 2);
        int yStart = 1 + row * (FONT_HEIGHT + 2);

        int color = ColorABGR.pack(colour[0], colour[1], colour[2], colour[3]);

        sink.quad(x, y, x + FONT_WIDTH, y + FONT_HEIGHT, Z_EPSILON, color,
            xStart / WIDTH, yStart / WIDTH, (xStart + FONT_WIDTH) / WIDTH, (yStart + FONT_HEIGHT) / WIDTH, RenderTypes.FULL_BRIGHT_LIGHTMAP);
        sink.quad(x, y, x + FONT_WIDTH, y + FONT_HEIGHT, Z_EPSILON, color,
            xStart / WIDTH, yStart / WIDTH, (xStart + FONT_WIDTH) / WIDTH, (yStart + FONT_HEIGHT) / WIDTH, RenderTypes.FULL_BRIGHT_LIGHTMAP
        );
    }

    private static void drawQuad(IrisTextVertexSink emitter, float x, float y, float width, float height, Palette palette, boolean greyscale, char colourIndex )
    {
        byte[] colour = palette.getByteColour( getColour( colourIndex, Colour.BLACK ), greyscale );
        int color = ColorABGR.pack(colour[0], colour[1], colour[2], colour[3]);
        quad( emitter, x, y, x + width, y + height, 0f, color, BACKGROUND_START, BACKGROUND_START, BACKGROUND_END, BACKGROUND_END );
    }

    private static void drawBackground(
        @Nonnull IrisTextVertexSink buffer, float x, float y, @Nonnull TextBuffer backgroundColour, @Nonnull Palette palette, boolean greyscale,
        float leftMarginSize, float rightMarginSize, float height
    )
    {
        if( leftMarginSize > 0 )
        {
            drawQuad( buffer, x - leftMarginSize, y, leftMarginSize, height, palette, greyscale, backgroundColour.charAt( 0 ) );
        }

        if( rightMarginSize > 0 )
        {
            drawQuad( buffer, x + backgroundColour.length() * FONT_WIDTH, y, rightMarginSize, height, palette, greyscale, backgroundColour.charAt( backgroundColour.length() - 1 ) );
        }

        // Batch together runs of identical background cells.
        int blockStart = 0;
        char blockColour = '\0';
        for( int i = 0; i < backgroundColour.length(); i++ )
        {
            char colourIndex = backgroundColour.charAt( i );
            if( colourIndex == blockColour ) continue;

            if( blockColour != '\0' )
            {
                drawQuad( buffer, x + blockStart * FONT_WIDTH, y, FONT_WIDTH * (i - blockStart), height, palette, greyscale, blockColour );
            }

            blockColour = colourIndex;
            blockStart = i;
        }

        if( blockColour != '\0' )
        {
            drawQuad( buffer, x + blockStart * FONT_WIDTH, y, FONT_WIDTH * (backgroundColour.length() - blockStart), height, palette, greyscale, blockColour );
        }
    }

    private static void drawString(@Nonnull IrisTextVertexSink buffer, float x, float y, @Nonnull TextBuffer text, @Nonnull TextBuffer textColour, @Nonnull Palette palette, boolean greyscale )
    {
        for( int i = 0; i < text.length(); i++ )
        {
            byte[] colour = palette.getByteColour( getColour( textColour.charAt( i ), Colour.BLACK ), greyscale );

            int index = text.charAt( i );
            if( index > 255 ) index = '?';
            drawChar( buffer, x + i * FONT_WIDTH, y, index, colour );
        }
    }

    public static void drawTerminalWithoutCursor(
        @Nonnull IrisTextVertexSink sink, float x, float y, @Nonnull Terminal terminal, boolean greyscale,
        float topMarginSize, float bottomMarginSize, float leftMarginSize, float rightMarginSize
    )
    {
        Palette palette = terminal.getPalette();
        int height = terminal.getHeight();

        // Top and bottom margins
        drawBackground(
            sink, x, y - topMarginSize, terminal.getBackgroundColourLine( 0 ), palette, greyscale,
            leftMarginSize, rightMarginSize, topMarginSize
        );

        drawBackground(
            sink, x, y + height * FONT_HEIGHT, terminal.getBackgroundColourLine( height - 1 ), palette, greyscale,
            leftMarginSize, rightMarginSize, bottomMarginSize
        );

        // The main text
        for( int i = 0; i < height; i++ )
        {
            float rowY = y + FONT_HEIGHT * i;
            drawBackground(
                sink, x, rowY, terminal.getBackgroundColourLine( i ), palette, greyscale,
                leftMarginSize, rightMarginSize, FONT_HEIGHT
            );
            drawString(
                sink, x, rowY, terminal.getLine( i ), terminal.getTextColourLine( i ),
                palette, greyscale
            );
        }
    }

    public static void drawCursor( @Nonnull IrisTextVertexSink sink, float x, float y, @Nonnull Terminal terminal, boolean greyscale )
    {
        if( isCursorVisible( terminal ) )
        {
            byte[] colour = terminal.getPalette().getByteColour( 15 - terminal.getTextColour(), greyscale );
            drawChar( sink, x + terminal.getCursorX() * FONT_WIDTH, y + terminal.getCursorY() * FONT_HEIGHT, '_', colour );
        }
    }

    public static int getVertexCount( Terminal terminal )
    {
        return (1 + (terminal.getHeight() + 2) * terminal.getWidth() * 2) * 4;
    }

    private static void quad(IrisTextVertexSink buffer, float x1, float y1, float x2, float y2, float z, int rgba, float u1, float v1, float u2, float v2 )
    {
        // Emit a single quad to our buffer. This uses Unsafe (well, LWJGL's MemoryUtil) to directly blit bytes to the
        // underlying buffer. This allows us to have a single bounds check up-front, rather than one for every write.
        // This provides significant performance gains, at the cost of well, using Unsafe.
        // Each vertex is 28 bytes, giving 112 bytes in total. Vertices are of the form (xyz:FFF)(rgba:BBBB)(uv1:FF)(uv2:SS),
        // which matches the POSITION_COLOR_TEX_LIGHTMAP vertex format.

        buffer.quad(x1,y1,x2,y2,z, rgba,u1,v1,u2,v2,RenderTypes.FULL_BRIGHT_LIGHTMAP);

        // Well done for getting to the end of this method. I recommend you take a break and go look at cute puppies.
    }
}
