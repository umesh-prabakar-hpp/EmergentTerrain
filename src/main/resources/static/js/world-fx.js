(function initWorldFxModule() {
  const TERRAIN_SHADER = /* wgsl */`
struct Uniforms {
  canvas_world: vec4<f32>,
  view: vec4<f32>,
  time_counts: vec4<f32>,
  flags_player: vec4<f32>,
  rect: vec4<f32>,
  markers: vec4<f32>,
};

struct ProposalData {
  a: vec4<f32>,
  b: vec4<f32>,
  c: vec4<f32>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(1) var<storage, read> tiles: array<u32>;
@group(0) @binding(2) var<storage, read> highways: array<u32>;
@group(0) @binding(3) var<storage, read> transitions: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read> proposals: array<ProposalData>;
@group(0) @binding(6) var<storage, read> routes: array<vec2<f32>>;

struct VertexOut {
  @builtin(position) position: vec4<f32>,
  @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOut {
  var positions = array<vec2<f32>, 3>(
    vec2<f32>(-1.0, -3.0),
    vec2<f32>(-1.0, 1.0),
    vec2<f32>(3.0, 1.0)
  );
  let clip = positions[vertex_index];
  var out: VertexOut;
  out.position = vec4<f32>(clip, 0.0, 1.0);
  out.uv = clip * 0.5 + vec2<f32>(0.5, 0.5);
  return out;
}

fn cell_index(cell: vec2<i32>) -> u32 {
  let world_w = max(1u, u32(uniforms.canvas_world.z));
  return u32(cell.y) * world_w + u32(cell.x);
}

fn tile_color(tile: f32) -> vec3<f32> {
  if (tile > 3.5) {
    return vec3<f32>(0.73, 0.56, 0.37);
  }
  if (tile > 2.5) {
    return vec3<f32>(0.39, 0.42, 0.47);
  }
  if (tile > 1.5) {
    return vec3<f32>(0.29, 0.64, 0.87);
  }
  if (tile > 0.5) {
    return vec3<f32>(0.16, 0.49, 0.39);
  }
  return vec3<f32>(0.89, 0.77, 0.56);
}

fn proposal_color(tile_type: f32, support: f32, status: f32) -> vec3<f32> {
  if (status > 1.5) {
    return vec3<f32>(0.93, 0.29, 0.31);
  }
  if (status > 0.5) {
    return vec3<f32>(0.98, 0.86, 0.37);
  }
  if (tile_type > 2.5) {
    return mix(vec3<f32>(0.88, 0.52, 0.33), vec3<f32>(0.97, 0.78, 0.44), support);
  }
  if (tile_type > 1.5) {
    return mix(vec3<f32>(0.24, 0.60, 0.98), vec3<f32>(0.59, 0.84, 0.98), support);
  }
  if (tile_type > 0.5) {
    return mix(vec3<f32>(0.23, 0.82, 0.54), vec3<f32>(0.73, 0.96, 0.69), support);
  }
  return mix(vec3<f32>(0.92, 0.72, 0.38), vec3<f32>(1.0, 0.88, 0.60), support);
}

fn hash21(p: vec2<f32>) -> f32 {
  let h = dot(p, vec2<f32>(127.1, 311.7));
  return fract(sin(h) * 43758.5453123);
}

fn distance_to_segment(p: vec2<f32>, a: vec2<f32>, b: vec2<f32>) -> f32 {
  let ab = b - a;
  let denom = max(dot(ab, ab), 0.0001);
  let t = clamp(dot(p - a, ab) / denom, 0.0, 1.0);
  return distance(p, a + ab * t);
}

@fragment
fn fs_main(in: VertexOut) -> @location(0) vec4<f32> {
  let canvas_size = max(uniforms.canvas_world.xy, vec2<f32>(1.0, 1.0));
  let world_size = max(uniforms.canvas_world.zw, vec2<f32>(1.0, 1.0));
  let is_mini = uniforms.flags_player.y > 0.5;
  let world_pos = select(
    uniforms.view.xy + vec2<f32>(in.uv.x * uniforms.view.z, in.uv.y * uniforms.view.w),
    vec2<f32>(in.uv.x * world_size.x, in.uv.y * world_size.y),
    is_mini
  );
  let cell = clamp(
    vec2<i32>(i32(floor(world_pos.x)), i32(floor(world_pos.y))),
    vec2<i32>(0, 0),
    vec2<i32>(i32(world_size.x) - 1, i32(world_size.y) - 1)
  );
  let idx = cell_index(cell);
  let transition = transitions[idx];
  let transition_active = abs(transition.y - transition.x) > 0.001 || transition.w > 0.001;
  let tile_value = select(f32(tiles[idx]), mix(transition.x, transition.y, clamp(transition.z, 0.0, 1.0)), transition_active);
  var color = tile_color(tile_value);

  let highway_active = highways[idx] > 0u || tile_value > 3.5;
  if (highway_active) {
    color = mix(color, vec3<f32>(0.50, 0.35, 0.21), 0.78);
    let road_band = 1.0 - smoothstep(0.15, 0.33, abs(fract(world_pos.y + 0.5) - 0.5));
    let center_line = 1.0 - smoothstep(0.015, 0.07, abs(fract(world_pos.y + 0.5) - 0.5));
    color += vec3<f32>(0.06, 0.05, 0.03) * road_band;
    color += vec3<f32>(0.20, 0.18, 0.12) * center_line;
  }

  if (transition_active && !is_mini) {
    let blend_glow = 1.0 - abs(clamp(transition.z, 0.0, 1.0) - 0.5) * 2.0;
    color += vec3<f32>(0.08, 0.11, 0.10) * blend_glow;
  }

  let proposal_count = u32(uniforms.time_counts.z);
  let time_sec = uniforms.time_counts.y;
  let local_cell = fract(world_pos);
  var emission = vec3<f32>(0.0, 0.0, 0.0);
  var field_alpha = 0.0;
  for (var i: u32 = 0u; i < proposal_count; i = i + 1u) {
    let proposal = proposals[i];
    let anchor = proposal.a.xy;
    let radius = max(3.0, proposal.a.z);
    let support = clamp(proposal.a.w, 0.0, 1.0);
    let stage = proposal.b.x;
    let status = proposal.b.y;
    let created = proposal.b.z;
    let expires = proposal.b.w;
    let resolved = proposal.c.x;
    let wave_strength = proposal.c.y;
    let tile_type = proposal.c.z;
    let dist = distance(world_pos + vec2<f32>(0.5, 0.5), anchor + vec2<f32>(0.5, 0.5));
    let field = 1.0 - smoothstep(0.0, radius * 1.75, dist);
    if (field <= 0.0001) {
      continue;
    }
    let pulse = 0.55 + 0.45 * sin(time_sec * (3.6 + stage * 0.45) - dist * (1.05 + support));
    let ring_age = max(0.0, time_sec - created);
    let ring_radius = min(radius * 1.85, ring_age * (2.2 + stage * 0.45));
    let ring = 1.0 - smoothstep(0.0, 0.42, abs(dist - ring_radius));
    let resolve_decay = select(1.0, clamp(1.0 - max(0.0, time_sec - resolved) / 1.35, 0.0, 1.0), status > 0.5);
    let time_left = max(0.0, expires - time_sec);
    let urgency = clamp(1.0 - time_left / 12.0, 0.0, 1.0);
    let base_strength = field * (0.28 + support * 0.52 + urgency * 0.15) * resolve_decay;
    let overlay = proposal_color(tile_type, support, status);
    emission += overlay * base_strength * (0.65 + 0.35 * pulse);
    emission += overlay * ring * wave_strength * 0.22 * resolve_decay;
    field_alpha = max(field_alpha, base_strength);

    let spark_seed = floor(world_pos * 2.0) + vec2<f32>(f32(i) * 17.0, floor(time_sec * 10.0));
    let spark = step(0.955, hash21(spark_seed + vec2<f32>(13.0, 7.0)));
    emission += overlay * spark * field * 0.18 * (0.4 + pulse * 0.6);
  }
  color = mix(color, color + emission, clamp(field_alpha * 0.95, 0.0, 0.95));

  if (!is_mini && uniforms.markers.z >= 0.0 && uniforms.markers.w >= 0.0) {
    let pending_match = cell.x == i32(uniforms.markers.z) && cell.y == i32(uniforms.markers.w);
    if (pending_match) {
      let border = max(
        1.0 - smoothstep(0.02, 0.14, local_cell.x),
        max(
          1.0 - smoothstep(0.02, 0.14, local_cell.y),
          max(
            1.0 - smoothstep(0.02, 0.14, 1.0 - local_cell.x),
            1.0 - smoothstep(0.02, 0.14, 1.0 - local_cell.y)
          )
        )
      );
      color = mix(color, vec3<f32>(0.98, 0.97, 0.93), border * 0.85);
    }
  }

  if (is_mini) {
    let player_pos = uniforms.flags_player.zw;
    let player_dot = 1.0 - smoothstep(0.0, 0.9, distance(world_pos + vec2<f32>(0.5, 0.5), player_pos + vec2<f32>(0.5, 0.5)));
    color = mix(color, vec3<f32>(1.0, 0.76, 0.14), player_dot * 0.95);

    if (uniforms.markers.x >= 0.0 && uniforms.markers.y >= 0.0) {
      let delta = abs((world_pos + vec2<f32>(0.5, 0.5)) - (uniforms.markers.xy + vec2<f32>(0.5, 0.5)));
      let cross = max(
        (1.0 - step(0.22, delta.x)) * (1.0 - smoothstep(0.0, 1.25, delta.y)),
        (1.0 - step(0.22, delta.y)) * (1.0 - smoothstep(0.0, 1.25, delta.x))
      );
      color = mix(color, vec3<f32>(0.97, 0.98, 1.0), cross * 0.85);
    }

    let route_count = u32(uniforms.time_counts.w);
    if (route_count > 1u) {
      var route_glow = 0.0;
      for (var i: u32 = 0u; i + 1u < route_count; i = i + 1u) {
        let d = distance_to_segment(world_pos + vec2<f32>(0.5, 0.5), routes[i] + vec2<f32>(0.5, 0.5), routes[i + 1u] + vec2<f32>(0.5, 0.5));
        route_glow = max(route_glow, 1.0 - smoothstep(0.0, 0.8, d));
      }
      color = mix(color, vec3<f32>(0.38, 0.88, 0.44), route_glow * 0.8);
    }

    let rect_min = uniforms.rect.xy;
    let rect_max = uniforms.rect.xy + uniforms.rect.zw;
    let px = world_pos.x + 0.5;
    let py = world_pos.y + 0.5;
    let on_vertical = abs(px - rect_min.x) < 0.6 || abs(px - rect_max.x) < 0.6;
    let on_horizontal = abs(py - rect_min.y) < 0.6 || abs(py - rect_max.y) < 0.6;
    let in_y = py >= rect_min.y && py <= rect_max.y;
    let in_x = px >= rect_min.x && px <= rect_max.x;
    if ((on_vertical && in_y) || (on_horizontal && in_x)) {
      color = mix(color, vec3<f32>(0.97, 0.98, 1.0), 0.82);
    }
  }

  let vignette = clamp(distance(in.uv, vec2<f32>(0.5, 0.5)) * 1.45, 0.0, 1.0);
  color *= 1.0 - vignette * 0.14;
  return vec4<f32>(color, 1.0);
}
`;

  const ACTOR_SHADER = /* wgsl */`
struct Uniforms {
  canvas_world: vec4<f32>,
  view: vec4<f32>,
  time_counts: vec4<f32>,
  flags_player: vec4<f32>,
  rect: vec4<f32>,
  markers: vec4<f32>,
};

struct ActorData {
  pos_radius_glow: vec4<f32>,
  color: vec4<f32>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(5) var<storage, read> actors: array<ActorData>;

struct VertexOut {
  @builtin(position) position: vec4<f32>,
  @location(0) local: vec2<f32>,
  @location(1) color: vec4<f32>,
  @location(2) glow: f32,
};

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32, @builtin(instance_index) instance_index: u32) -> VertexOut {
  let actor = actors[instance_index];
  var corners = array<vec2<f32>, 6>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>(1.0, -1.0),
    vec2<f32>(-1.0, 1.0),
    vec2<f32>(-1.0, 1.0),
    vec2<f32>(1.0, -1.0),
    vec2<f32>(1.0, 1.0)
  );
  let corner = corners[vertex_index];
  let center = (actor.pos_radius_glow.xy - uniforms.view.xy + vec2<f32>(0.5, 0.5)) / uniforms.view.zw;
  let base_clip = vec2<f32>(center.x * 2.0 - 1.0, 1.0 - center.y * 2.0);
  let radius_px = max(2.0, actor.pos_radius_glow.z * uniforms.time_counts.x);
  let offset_clip = vec2<f32>(
    corner.x * radius_px / max(uniforms.canvas_world.x, 1.0) * 2.0,
    -corner.y * radius_px / max(uniforms.canvas_world.y, 1.0) * 2.0
  );

  var out: VertexOut;
  out.position = vec4<f32>(base_clip + offset_clip, 0.0, 1.0);
  out.local = corner;
  out.color = actor.color;
  out.glow = actor.pos_radius_glow.w;
  return out;
}

@fragment
fn fs_main(in: VertexOut) -> @location(0) vec4<f32> {
  let d = length(in.local);
  if (d > 1.0) {
    discard;
  }
  let core = smoothstep(1.0, 0.42, d);
  let rim = smoothstep(0.92, 0.55, d) * in.glow;
  let color = in.color.rgb + vec3<f32>(0.14, 0.12, 0.10) * rim;
  let alpha = clamp(in.color.a * max(core, rim * 0.55), 0.0, 1.0);
  return vec4<f32>(color, alpha);
}
`;

  class WorldFxRenderer {
    static async create(mainCanvas, miniCanvas) {
      if (!navigator.gpu) {
        throw new Error('WebGPU is not available in this browser');
      }
      const adapter = await navigator.gpu.requestAdapter();
      if (!adapter) {
        throw new Error('No WebGPU adapter found');
      }
      const device = await adapter.requestDevice();
      const renderer = new WorldFxRenderer(device, mainCanvas, miniCanvas);
      await renderer.init();
      return renderer;
    }

    constructor(device, mainCanvas, miniCanvas) {
      this.device = device;
      this.mainCanvas = mainCanvas;
      this.miniCanvas = miniCanvas || null;
      this.ready = false;
      this.format = navigator.gpu.getPreferredCanvasFormat();
      this.mainContext = null;
      this.miniContext = null;
      this.uniformBuffer = null;
      this.tileBuffer = null;
      this.highwayBuffer = null;
      this.transitionBuffer = null;
      this.proposalBuffer = null;
      this.actorBuffer = null;
      this.routeBuffer = null;
      this.bindGroup = null;
      this.terrainPipeline = null;
      this.actorPipeline = null;
      this.lastTilesRef = null;
      this.lastHighwaysRef = null;
      this.worldWidth = 0;
      this.worldHeight = 0;
      this.flatTiles = new Uint32Array(1);
      this.flatHighways = new Uint32Array(1);
      this.transitionData = new Float32Array(4);
      this.transitionDefaults = new Float32Array(4);
      this.lastTransitionIndices = [];
    }

    async init() {
      this.mainContext = this.mainCanvas.getContext('webgpu');
      if (!this.mainContext) {
        throw new Error('Unable to get WebGPU context for world canvas');
      }
      this.mainContext.configure({
        device: this.device,
        format: this.format,
        alphaMode: 'premultiplied'
      });
      if (this.miniCanvas) {
        this.miniContext = this.miniCanvas.getContext('webgpu');
        if (this.miniContext) {
          this.miniContext.configure({
            device: this.device,
            format: this.format,
            alphaMode: 'premultiplied'
          });
        }
      }

      this.uniformBuffer = this.device.createBuffer({
        size: 96,
        usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST
      });

      const shaderModule = this.device.createShaderModule({ code: TERRAIN_SHADER });
      const actorShaderModule = this.device.createShaderModule({ code: ACTOR_SHADER });

      const bindGroupLayout = this.device.createBindGroupLayout({
        entries: [
          { binding: 0, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'uniform' } },
          { binding: 1, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
          { binding: 2, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
          { binding: 3, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
          { binding: 4, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
          { binding: 5, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } },
          { binding: 6, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'read-only-storage' } }
        ]
      });

      const pipelineLayout = this.device.createPipelineLayout({ bindGroupLayouts: [bindGroupLayout] });

      this.terrainPipeline = this.device.createRenderPipeline({
        layout: pipelineLayout,
        vertex: { module: shaderModule, entryPoint: 'vs_main' },
        fragment: {
          module: shaderModule,
          entryPoint: 'fs_main',
          targets: [{ format: this.format }]
        },
        primitive: { topology: 'triangle-list' }
      });

      this.actorPipeline = this.device.createRenderPipeline({
        layout: pipelineLayout,
        vertex: { module: actorShaderModule, entryPoint: 'vs_main' },
        fragment: {
          module: actorShaderModule,
          entryPoint: 'fs_main',
          targets: [{
            format: this.format,
            blend: {
              color: { srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha', operation: 'add' },
              alpha: { srcFactor: 'one', dstFactor: 'one-minus-src-alpha', operation: 'add' }
            }
          }]
        },
        primitive: { topology: 'triangle-list' }
      });

      this.ensureStorageBuffers(1, 1);
      this.ready = true;
    }

    resize() {
      if (this.mainContext) {
        this.mainContext.configure({
          device: this.device,
          format: this.format,
          alphaMode: 'premultiplied'
        });
      }
      if (this.miniContext) {
        this.miniContext.configure({
          device: this.device,
          format: this.format,
          alphaMode: 'premultiplied'
        });
      }
    }

    ensureBuffer(existing, size, usage) {
      const aligned = Math.max(4, Math.ceil(size / 4) * 4);
      if (existing && existing.size >= aligned) {
        return existing;
      }
      if (existing) {
        existing.destroy();
      }
      return this.device.createBuffer({ size: aligned, usage });
    }

    ensureStorageBuffers(worldWidth, worldHeight) {
      const cellCount = Math.max(1, worldWidth * worldHeight);
      this.tileBuffer = this.ensureBuffer(this.tileBuffer, cellCount * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.highwayBuffer = this.ensureBuffer(this.highwayBuffer, cellCount * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.transitionBuffer = this.ensureBuffer(this.transitionBuffer, cellCount * 16, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.proposalBuffer = this.ensureBuffer(this.proposalBuffer, 48, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.actorBuffer = this.ensureBuffer(this.actorBuffer, 32, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.routeBuffer = this.ensureBuffer(this.routeBuffer, 8, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);
      this.bindGroup = this.device.createBindGroup({
        layout: this.terrainPipeline.getBindGroupLayout(0),
        entries: [
          { binding: 0, resource: { buffer: this.uniformBuffer } },
          { binding: 1, resource: { buffer: this.tileBuffer } },
          { binding: 2, resource: { buffer: this.highwayBuffer } },
          { binding: 3, resource: { buffer: this.transitionBuffer } },
          { binding: 4, resource: { buffer: this.proposalBuffer } },
          { binding: 5, resource: { buffer: this.actorBuffer } },
          { binding: 6, resource: { buffer: this.routeBuffer } }
        ]
      });
    }

    flattenWorld(data) {
      const worldWidth = Math.max(1, data.worldWidth || 1);
      const worldHeight = Math.max(1, data.worldHeight || 1);
      if (data.tiles === this.lastTilesRef && data.highways === this.lastHighwaysRef && worldWidth === this.worldWidth && worldHeight === this.worldHeight) {
        return;
      }
      this.worldWidth = worldWidth;
      this.worldHeight = worldHeight;
      const cellCount = worldWidth * worldHeight;
      this.flatTiles = new Uint32Array(cellCount);
      this.flatHighways = new Uint32Array(cellCount);
      this.transitionData = new Float32Array(cellCount * 4);
      this.transitionDefaults = new Float32Array(cellCount * 4);
      for (let y = 0; y < worldHeight; y++) {
        const tileRow = data.tiles[y] || [];
        const highwayRow = data.highways[y] || [];
        for (let x = 0; x < worldWidth; x++) {
          const idx = y * worldWidth + x;
          const tileValue = tileRow[x] || 0;
          const highwayValue = highwayRow[x] ? 1 : 0;
          this.flatTiles[idx] = tileValue;
          this.flatHighways[idx] = highwayValue;
          this.transitionData[idx * 4] = tileValue;
          this.transitionData[idx * 4 + 1] = tileValue;
          this.transitionData[idx * 4 + 2] = 1;
          this.transitionData[idx * 4 + 3] = 0;
        }
      }
      this.transitionDefaults.set(this.transitionData);
      this.lastTransitionIndices = [];
      this.lastTilesRef = data.tiles;
      this.lastHighwaysRef = data.highways;
      this.ensureStorageBuffers(worldWidth, worldHeight);
      this.device.queue.writeBuffer(this.tileBuffer, 0, this.flatTiles);
      this.device.queue.writeBuffer(this.highwayBuffer, 0, this.flatHighways);
      this.device.queue.writeBuffer(this.transitionBuffer, 0, this.transitionData);
    }

    updateTransitions(nowMs, transitionsMap) {
      if (!this.transitionData || !this.transitionDefaults) {
        return;
      }
      for (const idx of this.lastTransitionIndices) {
        const base = idx * 4;
        this.transitionData[base] = this.transitionDefaults[base];
        this.transitionData[base + 1] = this.transitionDefaults[base + 1];
        this.transitionData[base + 2] = this.transitionDefaults[base + 2];
        this.transitionData[base + 3] = this.transitionDefaults[base + 3];
      }
      this.lastTransitionIndices = [];
      if (transitionsMap && transitionsMap.size) {
        transitionsMap.forEach((transition, key) => {
          const [xText, yText] = String(key).split(':');
          const x = Number.parseInt(xText, 10);
          const y = Number.parseInt(yText, 10);
          if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x >= this.worldWidth || y >= this.worldHeight) {
            return;
          }
          const idx = y * this.worldWidth + x;
          const base = idx * 4;
          const durationMs = transition.durationMs || 180;
          const progress = Math.max(0, Math.min(1, (nowMs - transition.startTs) / durationMs));
          this.transitionData[base] = transition.prevTile || 0;
          this.transitionData[base + 1] = transition.nextTile || 0;
          this.transitionData[base + 2] = progress;
          this.transitionData[base + 3] = transition.phase === 'committed' ? 1 : 0.5;
          this.lastTransitionIndices.push(idx);
        });
      }
      this.device.queue.writeBuffer(this.transitionBuffer, 0, this.transitionData);
    }

    updateDynamicBuffers(data) {
      const proposalValues = [];
      (data.proposals || []).forEach((proposal) => {
        proposalValues.push(
          proposal.anchorX || 0,
          proposal.anchorY || 0,
          proposal.radius || 4,
          proposal.support || 0.5,
          proposal.stage || 0,
          proposal.statusCode || 0,
          (proposal.createdAtMs || 0) / 1000,
          (proposal.expiresAtMs || 0) / 1000,
          (proposal.resolvedAtMs || 0) / 1000,
          proposal.waveStrength || 0.8,
          proposal.tileType || 0,
          0
        );
      });
      const proposalData = new Float32Array(proposalValues.length ? proposalValues : [0, 0, 4, 0.5, 0, 0, 0, 0, 0, 0.8, 0, 0]);
      this.proposalBuffer = this.ensureBuffer(this.proposalBuffer, proposalData.byteLength, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);

      const actorValues = [];
      (data.actors || []).forEach((actor) => {
        actorValues.push(
          actor.x || 0,
          actor.y || 0,
          actor.radius || 0.4,
          actor.glow || 0.2,
          ...(actor.color || [1, 1, 1, 1])
        );
      });
      const actorData = new Float32Array(actorValues.length ? actorValues : [0, 0, 0, 0, 0, 0, 0, 0]);
      this.actorBuffer = this.ensureBuffer(this.actorBuffer, actorData.byteLength, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);

      const route = data.pendingRoutes && Array.isArray(data.pendingRoutes.road) ? data.pendingRoutes.road : [];
      const routeValues = [];
      route.forEach((point) => {
        routeValues.push(point.x || 0, point.y || 0);
      });
      const routeData = new Float32Array(routeValues.length ? routeValues : [0, 0]);
      this.routeBuffer = this.ensureBuffer(this.routeBuffer, routeData.byteLength, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST);

      this.bindGroup = this.device.createBindGroup({
        layout: this.terrainPipeline.getBindGroupLayout(0),
        entries: [
          { binding: 0, resource: { buffer: this.uniformBuffer } },
          { binding: 1, resource: { buffer: this.tileBuffer } },
          { binding: 2, resource: { buffer: this.highwayBuffer } },
          { binding: 3, resource: { buffer: this.transitionBuffer } },
          { binding: 4, resource: { buffer: this.proposalBuffer } },
          { binding: 5, resource: { buffer: this.actorBuffer } },
          { binding: 6, resource: { buffer: this.routeBuffer } }
        ]
      });

      this.device.queue.writeBuffer(this.proposalBuffer, 0, proposalData);
      this.device.queue.writeBuffer(this.actorBuffer, 0, actorData);
      this.device.queue.writeBuffer(this.routeBuffer, 0, routeData);
    }

    writeUniforms(targetCanvas, data, isMini) {
      const values = new Float32Array(24);
      const player = data.player || { x: -1, y: -1 };
      const target = data.miniTarget || { x: -1, y: -1 };
      const pending = data.pendingChange || { x: -1, y: -1 };
      values.set([
        targetCanvas.width || 1,
        targetCanvas.height || 1,
        data.worldWidth || 1,
        data.worldHeight || 1,
        data.viewX || 0,
        data.viewY || 0,
        data.viewWidth || 1,
        data.viewHeight || 1,
        data.tileSize || 16,
        (data.nowMs || 0) / 1000,
        (data.proposals || []).length,
        data.pendingRoutes && Array.isArray(data.pendingRoutes.road) ? data.pendingRoutes.road.length : 0,
        (data.actors || []).length,
        isMini ? 1 : 0,
        Number.isFinite(player.x) ? player.x : -1,
        Number.isFinite(player.y) ? player.y : -1,
        data.viewX || 0,
        data.viewY || 0,
        data.viewWidth || 1,
        data.viewHeight || 1,
        Number.isFinite(target.x) ? target.x : -1,
        Number.isFinite(target.y) ? target.y : -1,
        Number.isFinite(pending.x) ? pending.x : -1,
        Number.isFinite(pending.y) ? pending.y : -1
      ]);
      this.device.queue.writeBuffer(this.uniformBuffer, 0, values);
    }

    drawPass(context, targetCanvas, data, isMini) {
      if (!context) {
        return;
      }
      this.writeUniforms(targetCanvas, data, isMini);
      const encoder = this.device.createCommandEncoder();
      const pass = encoder.beginRenderPass({
        colorAttachments: [{
          view: context.getCurrentTexture().createView(),
          clearValue: { r: 0, g: 0, b: 0, a: isMini ? 0.92 : 0 },
          loadOp: 'clear',
          storeOp: 'store'
        }]
      });
      pass.setBindGroup(0, this.bindGroup);
      pass.setPipeline(this.terrainPipeline);
      pass.draw(3);
      if (!isMini && data.actors && data.actors.length) {
        pass.setPipeline(this.actorPipeline);
        pass.draw(6, data.actors.length);
      }
      pass.end();
      this.device.queue.submit([encoder.finish()]);
    }

    render(data) {
      if (!this.ready || !data || !data.tiles || !data.tiles.length || !data.worldWidth || !data.worldHeight) {
        return;
      }
      this.flattenWorld(data);
      this.updateTransitions(data.nowMs || performance.now(), data.transitions);
      this.updateDynamicBuffers(data);
      this.drawPass(this.mainContext, this.mainCanvas, data, false);
      if (this.miniContext && this.miniCanvas) {
        this.drawPass(this.miniContext, this.miniCanvas, data, true);
      }
    }
  }

  window.WorldFxRenderer = WorldFxRenderer;
})();
