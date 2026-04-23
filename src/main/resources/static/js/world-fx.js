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
    static browserHint() {
      const ua = navigator.userAgent || '';
      if (/Firefox\//i.test(ua)) {
        return 'Firefox exposed navigator.gpu but did not return an adapter. This usually means WebGPU is gated by browser graphics policy, WebRender/Vulkan is not fully active for this session, or hardware acceleration is blocked.';
      }
      if (/Chrome\//i.test(ua) || /Chromium\//i.test(ua) || /Edg\//i.test(ua)) {
        return 'The browser exposed navigator.gpu but did not return an adapter. Hardware acceleration or the browser WebGPU backend may be unavailable for this session.';
      }
      return 'The browser exposed navigator.gpu but did not return an adapter. WebGPU is present at the API level, but no usable graphics backend was made available to the page.';
    }

    static async requestAdapterWithPreferences(options) {
      const attempts = [
        { powerPreference: 'high-performance', label: 'high-performance' },
        { powerPreference: 'low-power', label: 'low-power' },
        { powerPreference: undefined, label: 'default' }
      ];
      for (const attempt of attempts) {
        try {
          const adapter = attempt.powerPreference
            ? await navigator.gpu.requestAdapter({ powerPreference: attempt.powerPreference })
            : await navigator.gpu.requestAdapter();
          if (adapter) {
            WorldFxRenderer.emitStatus(options, {
              code: 'adapter_selected',
              level: 'warn',
              message: `Selected WebGPU adapter with ${attempt.label} preference.`,
              detail: `Chrome can expose multiple adapters on Linux. The renderer is trying ${attempt.label} first to avoid backend-specific failures on the default adapter.`
            });
            return adapter;
          }
        } catch (error) {
          console.warn(`WebGPU adapter request failed for ${attempt.label}:`, error);
        }
      }
      return null;
    }

    static emitStatus(options, status) {
      if (options && typeof options.onStatus === 'function') {
        options.onStatus(status);
      }
    }

    static async create(mainCanvas, miniCanvas, options = {}) {
      if (!navigator.gpu) {
        const error = new Error('navigator.gpu is unavailable in this browser.');
        WorldFxRenderer.emitStatus(options, {
          code: 'no_webgpu',
          level: 'error',
          message: 'WebGPU is unavailable in this browser.',
          detail: error.message
        });
        throw error;
      }
      const adapter = await WorldFxRenderer.requestAdapterWithPreferences(options);
      if (!adapter) {
        const error = new Error(WorldFxRenderer.browserHint());
        WorldFxRenderer.emitStatus(options, {
          code: 'no_adapter',
          level: 'error',
          message: 'No WebGPU adapter was found.',
          detail: error.message
        });
        throw error;
      }
      const device = await adapter.requestDevice();
      const renderer = new WorldFxRenderer(device, mainCanvas, miniCanvas, options);
      await renderer.init();
      return renderer;
    }

    constructor(device, mainCanvas, miniCanvas, options = {}) {
      this.device = device;
      this.mainCanvas = mainCanvas;
      this.miniCanvas = miniCanvas || null;
      this.onStatus = typeof options.onStatus === 'function' ? options.onStatus : null;
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
      this.disabledPasses = new Set();
      this.renderInFlight = false;
      this.pendingRenderData = null;
      this.surfaceGeneration = 0;
      this.destroyed = false;
      this.diagnostics = {
        code: 'initializing',
        level: 'warn',
        message: 'Starting WebGPU renderer...',
        detail: 'Preparing GPU device and canvases.',
        worldWidth: 0,
        worldHeight: 0,
        tileCount: 0,
        proposalCount: 0,
        actorCount: 0,
        frameCount: 0,
        lastFrameAt: 0
      };
      this.lastPublishedSignature = '';
      this.lastPublishedAt = 0;
      this.installDeviceDiagnostics();
    }

    installDeviceDiagnostics() {
      this.device.lost.then((info) => {
        if (this.destroyed) {
          return;
        }
        this.ready = false;
        const detail = info && info.message
          ? info.message
          : `Device lost${info && info.reason ? ` (${info.reason})` : ''}.`;
        console.error('WebGPU device lost:', info);
        this.publishStatus({
          code: 'device_lost',
          level: 'error',
          message: 'WebGPU device lost.',
          detail
        }, true);
      });
      this.device.addEventListener('uncapturederror', (event) => {
        if (this.destroyed) {
          return;
        }
        const detail = event && event.error && event.error.message
          ? event.error.message
          : 'Unknown uncaptured WebGPU error.';
        console.error('Uncaptured WebGPU error:', event.error || event);
        this.publishStatus({
          code: 'uncaptured_error',
          level: 'error',
          message: 'WebGPU reported an uncaptured error.',
          detail
        }, true);
      });
    }

    publishStatus(status, force = false) {
      this.diagnostics = {
        ...this.diagnostics,
        ...status
      };
      if (!this.onStatus) {
        return;
      }
      const signature = JSON.stringify([
        this.diagnostics.code,
        this.diagnostics.level,
        this.diagnostics.message,
        this.diagnostics.detail,
        this.diagnostics.worldWidth,
        this.diagnostics.worldHeight,
        this.diagnostics.tileCount,
        this.diagnostics.actorCount,
        this.diagnostics.proposalCount,
        this.diagnostics.frameCount
      ]);
      const now = Date.now();
      if (!force && signature === this.lastPublishedSignature && (now - this.lastPublishedAt) < 1000) {
        return;
      }
      this.lastPublishedSignature = signature;
      this.lastPublishedAt = now;
      this.onStatus({ ...this.diagnostics });
    }

    getDiagnostics() {
      return { ...this.diagnostics };
    }

    async init() {
      this.mainContext = this.mainCanvas.getContext('webgpu');
      if (!this.mainContext) {
        const error = new Error('Unable to get a WebGPU context for #worldFx.');
        this.publishStatus({
          code: 'main_context_failed',
          level: 'error',
          message: 'WebGPU canvas context creation failed.',
          detail: error.message
        }, true);
        throw error;
      }
      this.mainContext.configure({
        device: this.device,
        format: this.format,
        alphaMode: 'opaque'
      });
      if (this.miniCanvas) {
        this.miniContext = this.miniCanvas.getContext('webgpu');
        if (this.miniContext) {
          this.miniContext.configure({
            device: this.device,
            format: this.format,
            alphaMode: 'opaque'
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
      this.publishStatus({
        code: 'waiting_for_world',
        level: 'warn',
        message: 'WebGPU ready, waiting for room state.',
        detail: 'The GPU pipeline initialized successfully but no world frame has been submitted yet.'
      }, true);
    }

    resize() {
      if (this.destroyed) {
        return;
      }
      this.surfaceGeneration += 1;
      if (this.mainContext) {
        this.mainContext.configure({
          device: this.device,
          format: this.format,
          alphaMode: 'opaque'
        });
      }
      if (this.miniContext) {
        this.miniContext.configure({
          device: this.device,
          format: this.format,
          alphaMode: 'opaque'
        });
      }
      this.disabledPasses.clear();
    }

    destroy() {
      this.destroyed = true;
      this.ready = false;
      this.pendingRenderData = null;
      this.renderInFlight = false;
      this.surfaceGeneration += 1;
      this.disabledPasses.clear();
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

    describeCanvas(targetCanvas, label) {
      return `${label} ${targetCanvas.width || 0}x${targetCanvas.height || 0}`;
    }

    async drawPass(context, targetCanvas, data, isMini, label, allowReconfigure = true) {
      if (this.destroyed) {
        return false;
      }
      if (!context) {
        return false;
      }
      if ((targetCanvas.width || 0) < 1 || (targetCanvas.height || 0) < 1) {
        this.publishStatus({
          code: 'canvas_zero_sized',
          level: 'warn',
          message: `Skipping ${label} pass because its canvas has no drawable size.`,
          detail: this.describeCanvas(targetCanvas, label)
        }, true);
        return false;
      }
      try {
        const generation = this.surfaceGeneration;
        this.device.pushErrorScope('validation');
        if (this.destroyed || generation !== this.surfaceGeneration) {
          await this.device.popErrorScope();
          return false;
        }
        this.writeUniforms(targetCanvas, data, isMini);
        const texture = context.getCurrentTexture();
        const view = texture.createView();
        const encoder = this.device.createCommandEncoder();
        const pass = encoder.beginRenderPass({
          colorAttachments: [{
            view,
            clearValue: { r: 0, g: 0, b: 0, a: 1 },
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
        const commandBuffer = encoder.finish();
        if (this.destroyed || generation !== this.surfaceGeneration) {
          await this.device.popErrorScope();
          return false;
        }
        this.device.queue.submit([commandBuffer]);
        const validationError = await this.device.popErrorScope();
        if (this.destroyed || generation !== this.surfaceGeneration) {
          return false;
        }
        if (validationError) {
          throw validationError;
        }
        return true;
      } catch (error) {
        const detail = `${error && error.message ? error.message : String(error)} | ${this.describeCanvas(targetCanvas, label)}`;
        console.error(`WebGPU draw pass failed for ${label}:`, error);
        try {
          await this.device.popErrorScope();
        } catch (_) {
          // The scope may already be resolved if submission reached validation.
        }
        if (allowReconfigure) {
          try {
            this.resize();
            this.publishStatus({
              code: 'render_pass_reconfigure',
              level: 'warn',
              message: `Retrying ${label} pass after canvas reconfigure.`,
              detail
            }, true);
          } catch (resizeError) {
            console.error(`WebGPU reconfigure failed for ${label}:`, resizeError);
          }
          return this.drawPass(context, targetCanvas, data, isMini, label, false);
        }
        this.publishStatus({
          code: 'draw_pass_failed',
          level: 'error',
          message: `WebGPU render pass failed (${label}).`,
          detail
        }, true);
        if (isMini) {
          this.disabledPasses.add(label);
        }
        return false;
      }
    }

    render(data) {
      if (this.destroyed) {
        return;
      }
      this.pendingRenderData = data;
      if (this.renderInFlight) {
        return;
      }
      this.renderInFlight = true;
      void this.renderQueued();
    }

    async renderQueued() {
      while (this.pendingRenderData) {
        if (this.destroyed) {
          break;
        }
        const data = this.pendingRenderData;
        this.pendingRenderData = null;
        await this.renderNow(data);
      }
      this.renderInFlight = false;
    }

    async renderNow(data) {
      if (this.destroyed) {
        return;
      }
      if (!this.ready) {
        this.publishStatus({
          code: 'renderer_not_ready',
          level: this.diagnostics.level === 'error' ? 'error' : 'warn',
          message: 'WebGPU renderer is not ready.',
          detail: this.diagnostics.detail || 'The renderer cannot submit frames yet.'
        });
        return;
      }
      if (!data || !data.tiles || !data.tiles.length || !data.worldWidth || !data.worldHeight) {
        this.publishStatus({
          code: 'waiting_for_world',
          level: 'warn',
          message: 'Waiting for world data.',
          detail: 'No tile payload is available for the GPU renderer yet.',
          worldWidth: data && data.worldWidth ? data.worldWidth : 0,
          worldHeight: data && data.worldHeight ? data.worldHeight : 0,
          tileCount: 0,
          proposalCount: data && data.proposals ? data.proposals.length : 0,
          actorCount: data && data.actors ? data.actors.length : 0
        });
        return;
      }
      const tileRows = data.tiles.length;
      const tileCols = data.tiles[0] ? data.tiles[0].length : 0;
      if (tileRows !== data.worldHeight || tileCols !== data.worldWidth) {
        const detail = `World payload mismatch: data.world=${data.worldWidth}x${data.worldHeight}, tiles=${tileCols}x${tileRows}.`;
        console.error(detail, data);
        this.publishStatus({
          code: 'invalid_world_dimensions',
          level: 'error',
          message: 'Renderer rejected the world payload.',
          detail,
          worldWidth: data.worldWidth,
          worldHeight: data.worldHeight,
          tileCount: tileRows * tileCols,
          proposalCount: data.proposals ? data.proposals.length : 0,
          actorCount: data.actors ? data.actors.length : 0
        }, true);
        return;
      }
      const highwayRows = data.highways && data.highways.length ? data.highways.length : 0;
      const highwayCols = highwayRows && data.highways[0] ? data.highways[0].length : 0;
      if (highwayRows && (highwayRows !== data.worldHeight || highwayCols !== data.worldWidth)) {
        const detail = `Highway payload mismatch: expected ${data.worldWidth}x${data.worldHeight}, got ${highwayCols}x${highwayRows}.`;
        console.error(detail, data.highways);
        this.publishStatus({
          code: 'invalid_highway_dimensions',
          level: 'error',
          message: 'Renderer rejected the highway payload.',
          detail,
          worldWidth: data.worldWidth,
          worldHeight: data.worldHeight,
          tileCount: tileRows * tileCols,
          proposalCount: data.proposals ? data.proposals.length : 0,
          actorCount: data.actors ? data.actors.length : 0
        }, true);
        return;
      }
      if (!this.bindGroup || !this.terrainPipeline || !this.actorPipeline) {
        this.publishStatus({
          code: 'pipeline_unavailable',
          level: 'error',
          message: 'WebGPU pipelines are unavailable.',
          detail: 'The renderer cannot submit frames because its bind group or pipelines are missing.'
        }, true);
        return;
      }
      this.flattenWorld(data);
      this.updateTransitions(data.nowMs || performance.now(), data.transitions);
      this.updateDynamicBuffers(data);
      const mainOk = await this.drawPass(this.mainContext, this.mainCanvas, data, false, 'main');
      if (!mainOk) {
        return;
      }
      if (this.miniContext && this.miniCanvas && !this.disabledPasses.has('mini')) {
        await this.drawPass(this.miniContext, this.miniCanvas, data, true, 'mini');
      }
      this.publishStatus({
        code: 'ready',
        level: 'ready',
        message: 'WebGPU renderer active.',
        detail: 'Frames are being submitted successfully.',
        worldWidth: data.worldWidth,
        worldHeight: data.worldHeight,
        tileCount: data.worldWidth * data.worldHeight,
        proposalCount: data.proposals ? data.proposals.length : 0,
        actorCount: data.actors ? data.actors.length : 0,
        frameCount: (this.diagnostics.frameCount || 0) + 1,
        lastFrameAt: Date.now()
      });
    }
  }

  window.WorldFxRenderer = WorldFxRenderer;
})();
