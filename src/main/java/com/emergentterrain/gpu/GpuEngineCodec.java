package com.emergentterrain.gpu;

import com.emergentterrain.gpu.v1.BoolGrid;
import com.emergentterrain.gpu.v1.TileEncoding;
import com.emergentterrain.gpu.v1.TileGrid;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class GpuEngineCodec {
    private GpuEngineCodec() {
    }

    public static TileGrid toTileGrid(int[][] tiles) {
        int height = tiles.length;
        int width = height == 0 ? 0 : tiles[0].length;
        byte[] data = new byte[width * height];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                data[idx++] = (byte) tiles[y][x];
            }
        }
        return TileGrid.newBuilder()
            .setWidth(width)
            .setHeight(height)
            .setEncoding(TileEncoding.TILE_ENCODING_U8)
            .setTiles(ByteString.copyFrom(data))
            .build();
    }

    public static BoolGrid toBoolGrid(boolean[][] values, int width, int height) {
        byte[] data = new byte[width * height];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean v = y < values.length && x < values[y].length && values[y][x];
                data[idx++] = (byte) (v ? 1 : 0);
            }
        }
        return BoolGrid.newBuilder()
            .setWidth(width)
            .setHeight(height)
            .setValues(ByteString.copyFrom(data))
            .build();
    }

    public static int[][] fromTileGrid(TileGrid grid) {
        if (grid == null) {
            return new int[0][0];
        }
        int width = (int) grid.getWidth();
        int height = (int) grid.getHeight();
        if (width <= 0 || height <= 0) {
            return new int[0][0];
        }
        byte[] bytes = grid.getTiles().toByteArray();
        int[][] tiles = new int[height][width];
        TileEncoding encoding = grid.getEncoding();
        if (encoding == TileEncoding.TILE_ENCODING_I32 || bytes.length == width * height * 4) {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (buf.remaining() >= 4) {
                        tiles[y][x] = buf.getInt();
                    } else {
                        tiles[y][x] = 0;
                    }
                }
            }
            return tiles;
        }
        int idx = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (idx < bytes.length) {
                    tiles[y][x] = bytes[idx++] & 0xff;
                } else {
                    tiles[y][x] = 0;
                }
            }
        }
        return tiles;
    }

    public static boolean[][] fromBoolGrid(BoolGrid grid, int width, int height) {
        boolean[][] out = new boolean[height][width];
        if (grid == null) {
            return out;
        }
        byte[] bytes = grid.getValues().toByteArray();
        int idx = 0;
        int h = Math.min(height, (int) grid.getHeight());
        int w = Math.min(width, (int) grid.getWidth());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (idx < bytes.length) {
                    out[y][x] = bytes[idx++] != 0;
                }
            }
        }
        return out;
    }
}
