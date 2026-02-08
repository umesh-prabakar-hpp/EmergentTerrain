package com.emergentterrain.service;

import com.emergentterrain.web.GameStateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class WorldService {
    private final int width;
    private final int height;

    public WorldService(@Value("${app.world.width}") int width,
                        @Value("${app.world.height}") int height) {
        this.width = width;
        this.height = height;
    }

    public GameStateResponse generateWorld(Payload payload, long seed) {
        HiddenProperties hp = payload.hidden();
        double forestDensity = 0.30 + 0.50 * hp.humidity();
        double clusterBias = 0.20 + 0.60 * hp.density();

        int[][] tiles = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double noise = hashNoise(seed, x, y);
                tiles[y][x] = (noise < forestDensity) ? 1 : 0;
            }
        }

        tiles = smooth(tiles, clusterBias);

        Map<String, Double> props = new HashMap<>();
        props.put("forestDensity", forestDensity);
        props.put("clusterBias", clusterBias);
        props.put("humidity", hp.humidity());
        props.put("density", hp.density());
        props.put("temperature", hp.temperature());
        props.put("pressure", hp.pressure());
        props.put("energy", hp.energy());
        props.put("entropy", hp.entropy());

        GameStateResponse response = new GameStateResponse();
        response.setSeed(seed);
        response.setWidth(width);
        response.setHeight(height);
        response.setTiles(tiles);
        response.setProperties(props);
        return response;
    }

    private int[][] smooth(int[][] tiles, double clusterBias) {
        int h = tiles.length;
        int w = tiles[0].length;
        int[][] out = new int[h][w];
        int threshold = (int) (3 + clusterBias * 3);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int forestNeighbors = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            forestNeighbors += tiles[ny][nx];
                        }
                    }
                }
                out[y][x] = forestNeighbors >= threshold ? 1 : 0;
            }
        }
        return out;
    }

    private double hashNoise(long seed, int x, int y) {
        long n = seed;
        n ^= x * 374761393L;
        n ^= y * 668265263L;
        n = (n ^ (n >> 13)) * 1274126177L;
        long v = (n ^ (n >> 16)) & 0x7fffffffL;
        return (double) v / (double) Integer.MAX_VALUE;
    }
}
