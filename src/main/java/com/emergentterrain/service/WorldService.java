package com.emergentterrain.service;

import com.emergentterrain.web.GameStateResponse;
import com.emergentterrain.web.PlayerPosition;
import com.emergentterrain.web.RegionInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
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
        double waterDensity = 0.05 + 0.20 * hp.humidity() + 0.10 * hp.energy();

        int[][] tiles = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double noise = hashNoise(seed, x, y);
                tiles[y][x] = (noise < forestDensity) ? 1 : 0;
            }
        }

        tiles = smooth(tiles, clusterBias);
        placeWater(seed, tiles, waterDensity);

        int regionsCount = 6;
        int[][] regionIds = assignRegions(seed, regionsCount);
        List<RegionInfo> regions = summarizeRegions(regionIds, tiles, payload);

        boolean[][] fog = initialFog(seed);
        PlayerPosition player = new PlayerPosition(width / 2, height / 2);
        clearFog(fog, player.getX(), player.getY(), 2);

        Map<String, Double> props = new HashMap<>();
        props.put("forestDensity", forestDensity);
        props.put("clusterBias", clusterBias);
        props.put("waterDensity", waterDensity);
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
        response.setRegionIds(regionIds);
        response.setRegions(regions);
        response.setFog(fog);
        response.setPlayer(player);
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

    private void placeWater(long seed, int[][] tiles, double waterDensity) {
        int h = tiles.length;
        int w = tiles[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double n = hashNoise(seed + 1337, x, y);
                if (n < waterDensity) {
                    tiles[y][x] = 2;
                }
            }
        }
    }

    private int[][] assignRegions(long seed, int regions) {
        int[][] regionIds = new int[height][width];
        int[] rx = new int[regions];
        int[] ry = new int[regions];
        for (int i = 0; i < regions; i++) {
            rx[i] = (int) (hashNoise(seed + 9000 + i, i, 3) * width);
            ry[i] = (int) (hashNoise(seed + 9000 + i, i, 7) * height);
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int best = 0;
                double bestDist = Double.MAX_VALUE;
                for (int i = 0; i < regions; i++) {
                    double dx = x - rx[i];
                    double dy = y - ry[i];
                    double d = dx * dx + dy * dy;
                    if (d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
                regionIds[y][x] = best;
            }
        }
        return regionIds;
    }

    private List<RegionInfo> summarizeRegions(int[][] regionIds, int[][] tiles, Payload payload) {
        int regions = 0;
        for (int y = 0; y < regionIds.length; y++) {
            for (int x = 0; x < regionIds[0].length; x++) {
                regions = Math.max(regions, regionIds[y][x] + 1);
            }
        }
        int[] total = new int[regions];
        int[] forest = new int[regions];
        int[] water = new int[regions];
        for (int y = 0; y < regionIds.length; y++) {
            for (int x = 0; x < regionIds[0].length; x++) {
                int r = regionIds[y][x];
                total[r] += 1;
                if (tiles[y][x] == 1) forest[r] += 1;
                if (tiles[y][x] == 2) water[r] += 1;
            }
        }
        List<RegionInfo> infos = new ArrayList<>();
        for (int i = 0; i < regions; i++) {
            double forestDensity = total[i] == 0 ? 0 : (double) forest[i] / total[i];
            double waterDensity = total[i] == 0 ? 0 : (double) water[i] / total[i];
            double clearDensity = 1.0 - forestDensity - waterDensity;
            RegionInfo info = new RegionInfo();
            info.setId(i);
            info.setForestDensity(forestDensity);
            info.setWaterDensity(waterDensity);
            info.setClearDensity(clearDensity);
            String name;
            String meaning;
            if (forestDensity >= waterDensity && forestDensity >= clearDensity) {
                name = "Dense Forest Cluster";
                meaning = "Comfort with complexity / introspection";
            } else if (waterDensity >= forestDensity && waterDensity >= clearDensity) {
                name = "Water Pocket";
                meaning = "Emotional flow / adaptability";
            } else {
                name = "Open Clearing";
                meaning = "Desire for freedom / clarity";
            }
            info.setName(name);
            info.setMeaning(meaning);
            info.setExplanation(buildRegionExplanation(info, payload));
            infos.add(info);
        }
        return infos;
    }

    private String buildRegionExplanation(RegionInfo info, Payload payload) {
        boolean likesForests = payload.answers().getOrDefault("likes_forests", false);
        boolean likesOpen = payload.answers().getOrDefault("likes_open_fields", false);
        boolean likesWater = payload.answers().getOrDefault("likes_water", false);
        if (info.getName().contains("Forest")) {
            if (likesForests && !likesOpen) {
                return "This dense forest formed where multiple preferences reinforce each other.";
            }
            return "This forest cluster reflects an attraction to layered, immersive spaces.";
        }
        if (info.getName().contains("Water")) {
            if (likesWater) {
                return "These water pockets formed where emotional flow is amplified.";
            }
            return "These water pockets suggest adaptability and subtle shifts.";
        }
        if (likesOpen) {
            return "This open clearing reflects a desire for freedom and clarity.";
        }
        return "This clearing opens space where your preferences seek balance.";
    }

    private boolean[][] initialFog(long seed) {
        boolean[][] fog = new boolean[height][width];
        double fogDensity = 0.65;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                fog[y][x] = hashNoise(seed + 5555, x, y) < fogDensity;
            }
        }
        return fog;
    }

    private void clearFog(boolean[][] fog, int cx, int cy, int radius) {
        for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius) {
                    fog[y][x] = false;
                }
            }
        }
    }
}
