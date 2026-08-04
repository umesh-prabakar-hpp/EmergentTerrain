package com.emergentterrain.service;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.GpuEngineCodec;
import com.emergentterrain.gpu.v1.GenerateRegionRequest;
import com.emergentterrain.gpu.v1.GenerateRegionResponse;
import com.emergentterrain.gpu.v1.Region;
import com.emergentterrain.web.GameStateResponse;
import com.emergentterrain.web.PlayerPosition;
import com.emergentterrain.web.RegionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorldService {
    private static final Logger log = LoggerFactory.getLogger(WorldService.class);
    private final int width;
    private final int height;
    private final double highwayRatio;
    private final double aspectRatio;
    private final int maxTiles;
    private final boolean gpuEnabled;
    private final boolean gpuWorldEnabled;
    private final int gpuWorldMinCells;
    private final long worldCacheTtlMs;
    private final int worldCacheMaxEntries;
    private final ObjectMapper objectMapper;
    private final GpuEngineAdapter gpuEngineAdapter;
    private final ConcurrentHashMap<WorldCacheKey, CachedWorldResponse> worldCache = new ConcurrentHashMap<>();

    public WorldService(@Value("${app.world.width}") int width,
                        @Value("${app.world.height}") int height,
                        @Value("${app.world.auto:true}") boolean auto,
                        @Value("${app.world.maxTiles:120000}") int maxTiles,
                        @Value("${app.world.aspectRatio:1.3333}") double aspectRatio,
                        @Value("${app.world.highwayRatio:0.02}") double highwayRatio,
                        @Value("${app.world.cacheTtlMs:5000}") long worldCacheTtlMs,
                        @Value("${app.world.cacheMaxEntries:32}") int worldCacheMaxEntries,
                        @Value("${app.gpu.enabled:false}") boolean gpuEnabled,
                        @Value("${app.gpu.world.enabled:false}") boolean gpuWorldEnabled,
                        @Value("${app.gpu.world.minCells:30000}") int gpuWorldMinCells,
                        ObjectMapper objectMapper,
                        GpuEngineAdapter gpuEngineAdapter) {
        this.highwayRatio = highwayRatio;
        this.aspectRatio = aspectRatio <= 0 ? 1.3333 : aspectRatio;
        this.maxTiles = Math.max(20000, maxTiles);
        this.worldCacheTtlMs = Math.max(0, worldCacheTtlMs);
        this.worldCacheMaxEntries = Math.max(1, worldCacheMaxEntries);
        this.gpuEnabled = gpuEnabled;
        this.gpuWorldEnabled = gpuWorldEnabled;
        this.gpuWorldMinCells = Math.max(0, gpuWorldMinCells);
        this.objectMapper = objectMapper;
        this.gpuEngineAdapter = gpuEngineAdapter;
        if (auto) {
            int[] dims = computeWorldSize(this.maxTiles, this.aspectRatio);
            this.width = dims[0];
            this.height = dims[1];
        } else {
            this.width = width;
            this.height = height;
        }
    }

    public GameStateResponse generateWorld(Payload payload, long seed) {
        WorldCacheKey cacheKey = new WorldCacheKey(seed, width, height, shouldUseGpuWorld(width, height), payload.hidden());
        GameStateResponse cached = getCachedWorld(cacheKey);
        if (cached != null) {
            return cached;
        }
        HiddenProperties hp = payload.hidden();
        double forestDensity = 0.30 + 0.50 * hp.humidity();
        double clusterBias = 0.20 + 0.60 * hp.density();
        double waterDensity = 0.05 + 0.20 * hp.humidity() + 0.10 * hp.energy();
        double industryDensity = 0.02 + 0.06 * hp.pressure();

        GpuWorldResult gpuWorld = tryGenerateWithGpu(seed, width, height, hp);
        int[][] tiles;
        boolean[][] highways;
        if (gpuWorld != null) {
            tiles = gpuWorld.tiles;
            highways = gpuWorld.highways;
        } else {
            tiles = new int[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double noise = hashNoise(seed, x, y);
                    tiles[y][x] = (noise < forestDensity) ? 1 : 0;
                }
            }

            tiles = smooth(tiles, clusterBias);
            placeWater(seed, tiles, waterDensity);
            placeIndustry(seed, tiles, industryDensity);
            highways = generateHighways(seed);
        }

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
        props.put("industryDensity", industryDensity);
        props.put("highwayRatio", highwayRatio);

        GameStateResponse response = new GameStateResponse();
        response.setSeed(seed);
        response.setWidth(width);
        response.setHeight(height);
        response.setTiles(tiles);
        response.setHighways(highways);
        response.setRegionIds(regionIds);
        response.setRegions(regions);
        response.setFog(fog);
        response.setPlayer(player);
        response.setProperties(props);
        cacheWorld(cacheKey, response);
        return response;
    }

    private GpuWorldResult tryGenerateWithGpu(long seed, int width, int height, HiddenProperties hp) {
        if (!shouldUseGpuWorld(width, height)) {
            return null;
        }
        try {
            String paramsJson = buildGpuWorldParams(hp, width, height);
            GenerateRegionRequest request = GenerateRegionRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setSeed(seed)
                .setRegion(Region.newBuilder().setX(0).setY(0).setWidth(width).setHeight(height))
                .setParamsJson(paramsJson)
                .build();
            GenerateRegionResponse response = gpuEngineAdapter.generateRegion(request);
            if (!response.hasTiles()) {
                return null;
            }
            int[][] tiles = GpuEngineCodec.fromTileGrid(response.getTiles());
            boolean[][] highways = GpuEngineCodec.fromBoolGrid(response.getHighways(), width, height);
            if (tiles.length == 0 || tiles[0].length == 0) {
                return null;
            }
            if (tiles.length != height || tiles[0].length != width) {
                log.warn("gpu world size mismatch expected={}x{} actual={}x{}", width, height, tiles[0].length, tiles.length);
                return null;
            }
            return new GpuWorldResult(tiles, highways);
        } catch (Exception e) {
            log.warn("gpu world generation failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean shouldUseGpuWorld(int width, int height) {
        return gpuEnabled
            && gpuWorldEnabled
            && ((long) width * (long) height) >= gpuWorldMinCells;
    }

    private GameStateResponse getCachedWorld(WorldCacheKey key) {
        if (worldCacheTtlMs <= 0) {
            return null;
        }
        CachedWorldResponse cached = worldCache.get(key);
        if (cached == null) {
            return null;
        }
        if ((System.currentTimeMillis() - cached.createdAtMs) > worldCacheTtlMs) {
            worldCache.remove(key, cached);
            return null;
        }
        return copyResponse(cached.response);
    }

    private void cacheWorld(WorldCacheKey key, GameStateResponse response) {
        if (worldCacheTtlMs <= 0) {
            return;
        }
        if (worldCache.size() >= worldCacheMaxEntries) {
            pruneExpiredCacheEntries();
            if (worldCache.size() >= worldCacheMaxEntries) {
                WorldCacheKey oldestKey = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<WorldCacheKey, CachedWorldResponse> entry : worldCache.entrySet()) {
                    if (entry.getValue().createdAtMs < oldestAt) {
                        oldestAt = entry.getValue().createdAtMs;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    worldCache.remove(oldestKey);
                }
            }
        }
        worldCache.put(key, new CachedWorldResponse(copyResponse(response), System.currentTimeMillis()));
    }

    private void pruneExpiredCacheEntries() {
        long now = System.currentTimeMillis();
        worldCache.entrySet().removeIf(entry -> (now - entry.getValue().createdAtMs) > worldCacheTtlMs);
    }

    private GameStateResponse copyResponse(GameStateResponse source) {
        GameStateResponse copy = new GameStateResponse();
        copy.setSeed(source.getSeed());
        copy.setWidth(source.getWidth());
        copy.setHeight(source.getHeight());
        copy.setTiles(copyTiles(source.getTiles()));
        copy.setHighways(copyHighways(source.getHighways()));
        copy.setRegionIds(copyTiles(source.getRegionIds()));
        copy.setFog(copyHighways(source.getFog()));
        copy.setRegions(source.getRegions() == null ? null : new ArrayList<>(source.getRegions()));
        if (source.getPlayer() != null) {
            copy.setPlayer(new PlayerPosition(source.getPlayer().getX(), source.getPlayer().getY()));
        }
        copy.setSnapshots(source.getSnapshots() == null ? null : new ArrayList<>(source.getSnapshots()));
        copy.setQuestState(source.getQuestState());
        copy.setProperties(source.getProperties() == null ? null : new HashMap<>(source.getProperties()));
        return copy;
    }

    private int[][] copyTiles(int[][] source) {
        if (source == null) {
            return null;
        }
        int[][] copy = new int[source.length][];
        for (int y = 0; y < source.length; y++) {
            copy[y] = source[y] == null ? null : source[y].clone();
        }
        return copy;
    }

    private boolean[][] copyHighways(boolean[][] source) {
        if (source == null) {
            return null;
        }
        boolean[][] copy = new boolean[source.length][];
        for (int y = 0; y < source.length; y++) {
            copy[y] = source[y] == null ? null : source[y].clone();
        }
        return copy;
    }

    private String buildGpuWorldParams(HiddenProperties hp, int width, int height) {
        try {
            Map<String, Object> params = new HashMap<>();
            Map<String, Double> hidden = new HashMap<>();
            hidden.put("pressure", hp.pressure());
            hidden.put("temperature", hp.temperature());
            hidden.put("humidity", hp.humidity());
            hidden.put("density", hp.density());
            hidden.put("energy", hp.energy());
            hidden.put("entropy", hp.entropy());
            params.put("hidden", hidden);
            params.put("width", width);
            params.put("height", height);
            params.put("highwayRatio", highwayRatio);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static final class GpuWorldResult {
        private final int[][] tiles;
        private final boolean[][] highways;

        private GpuWorldResult(int[][] tiles, boolean[][] highways) {
            this.tiles = tiles;
            this.highways = highways;
        }
    }

    private record WorldCacheKey(long seed, int width, int height, boolean gpuWorld, HiddenProperties hiddenProperties) {
    }

    private static final class CachedWorldResponse {
        private final GameStateResponse response;
        private final long createdAtMs;

        private CachedWorldResponse(GameStateResponse response, long createdAtMs) {
            this.response = response;
            this.createdAtMs = createdAtMs;
        }
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

    private void placeIndustry(long seed, int[][] tiles, double industryDensity) {
        int h = tiles.length;
        int w = tiles[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (tiles[y][x] == 2) {
                    continue;
                }
                double n = hashNoise(seed + 2718, x, y);
                if (n < industryDensity) {
                    tiles[y][x] = 3;
                }
            }
        }
    }

    private boolean[][] generateHighways(long seed) {
        int h = height;
        int w = width;
        boolean[][] highways = new boolean[h][w];
        int target = Math.max(1, (int) Math.round(w * h * highwayRatio));
        int spacing = Math.max(8, (int) Math.round(Math.sqrt(2.0 / Math.max(0.01, highwayRatio))));

        int placed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x % spacing == 0 || y % spacing == 0) {
                    highways[y][x] = true;
                    placed++;
                }
            }
        }

        if (placed > target) {
            int prune = placed - target;
            int tries = 0;
            while (prune > 0 && tries < placed * 5) {
                int x = (int) (hashNoise(seed + 8888 + tries, tries, 41) * w);
                int y = (int) (hashNoise(seed + 8888 + tries, tries, 47) * h);
                if (highways[y][x] && (x % spacing != 0 || y % spacing != 0 || (tries % 3 == 0))) {
                    highways[y][x] = false;
                    prune--;
                }
                tries++;
            }
        }
        return highways;
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
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
        int[] industry = new int[regions];
        for (int y = 0; y < regionIds.length; y++) {
            for (int x = 0; x < regionIds[0].length; x++) {
                int r = regionIds[y][x];
                total[r] += 1;
                if (tiles[y][x] == 1) forest[r] += 1;
                if (tiles[y][x] == 2) water[r] += 1;
                if (tiles[y][x] == 3) industry[r] += 1;
            }
        }
        List<RegionInfo> infos = new ArrayList<>();
        for (int i = 0; i < regions; i++) {
            double forestDensity = total[i] == 0 ? 0 : (double) forest[i] / total[i];
            double waterDensity = total[i] == 0 ? 0 : (double) water[i] / total[i];
            double industryDensity = total[i] == 0 ? 0 : (double) industry[i] / total[i];
            double clearDensity = 1.0 - forestDensity - waterDensity - industryDensity;
            RegionInfo info = new RegionInfo();
            info.setId(i);
            info.setForestDensity(forestDensity);
            info.setWaterDensity(waterDensity);
            info.setClearDensity(clearDensity);
            info.setIndustryDensity(industryDensity);
            String name;
            String meaning;
            if (industryDensity >= forestDensity && industryDensity >= waterDensity && industryDensity >= clearDensity) {
                name = "Industry Grid";
                meaning = "Drive for structure / productivity";
            } else if (forestDensity >= waterDensity && forestDensity >= clearDensity) {
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
        boolean likesIndustry = payload.answers().getOrDefault("likes_industry", false);
        if (info.getName().contains("Industry")) {
            if (likesIndustry) {
                return "This industry grid forms where structure and output are reinforced.";
            }
            return "This industry grid suggests focus on systems and throughput.";
        }
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

    private int[] computeWorldSize(int maxTiles, double aspectRatio) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        int maxMb = (int) Math.max(128, maxMemory / (1024L * 1024L));
        int safeTiles = Math.min(maxTiles, maxMb * 220);
        safeTiles = Math.max(30000, safeTiles);
        double targetArea = safeTiles;
        double w = Math.sqrt(targetArea * aspectRatio);
        double h = w / aspectRatio;
        int width = Math.max(60, (int) Math.round(w));
        int height = Math.max(45, (int) Math.round(h));
        width = Math.min(width, 220);
        height = Math.min(height, 165);
        return new int[] { width, height };
    }
}
