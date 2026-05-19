// Phase 20.10 — externalize world-generation tunables so the operator can
// A/B different world flavors without a Rust rebuild.
//
// Loading: `WorldConfig::load()` reads from the path in env var
// `GPU_ENGINE_CONFIG` (default `./gpu.toml`). When the file is missing,
// returns `WorldConfig::default()` — the historical hardcoded values that
// `generate_region` used pre-Phase-20.10. When the file exists but parses
// malformed, prints a warning and falls back to defaults rather than
// failing startup; the engine is a perf service, not a config validator.

use serde::Deserialize;
use std::path::Path;

/// Top-level config. Sections are optional so a minimal `gpu.toml`
/// overriding only one knob doesn't need to spell out the others.
#[derive(Debug, Clone, Deserialize)]
pub struct WorldConfig {
    #[serde(default)]
    pub density: DensityCoefficients,
    #[serde(default)]
    pub hidden_defaults: HiddenParamDefaults,
    #[serde(default)]
    pub highways: HighwayConfig,
}

/// Forest / water / industry density derivation coefficients. The formula
/// in `generate_region` is:
///
///   forest_density   = forest.base   + forest.humidity_coef  * humidity + forest.density_coef  * density
///   water_density    = water.base    + water.humidity_coef   * humidity + water.energy_coef    * energy
///   industry_density = industry.base + industry.pressure_coef * pressure
///
/// All inputs are clamped to [0, 1] and the output is multiplied by u32::MAX
/// to produce the WGSL noise-threshold. Defaults are the pre-Phase-20.10
/// hardcoded values so a missing TOML reproduces today's world flavors.
#[derive(Debug, Clone, Deserialize)]
pub struct DensityCoefficients {
    #[serde(default)]
    pub forest: ForestCoefficients,
    #[serde(default)]
    pub water: WaterCoefficients,
    #[serde(default)]
    pub industry: IndustryCoefficients,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ForestCoefficients {
    pub base: f64,
    pub humidity_coef: f64,
    pub density_coef: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WaterCoefficients {
    pub base: f64,
    pub humidity_coef: f64,
    pub energy_coef: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct IndustryCoefficients {
    pub base: f64,
    pub pressure_coef: f64,
}

/// Defaults injected by `parse_hidden_params` when the request JSON omits
/// the corresponding key. Pre-Phase-20.10 these were all 0.5 hardcoded.
#[derive(Debug, Clone, Deserialize)]
pub struct HiddenParamDefaults {
    pub pressure: f64,
    pub humidity: f64,
    pub density: f64,
    pub energy: f64,
}

/// Highway-ratio default + spacing-formula clamps. The spacing formula in
/// `highway_spacing` is `(2.0 / ratio.clamp(min, max)).sqrt().round().max(min_spacing) as u32`.
#[derive(Debug, Clone, Deserialize)]
pub struct HighwayConfig {
    pub ratio_default: f64,
    pub ratio_min_clamp: f64,
    pub ratio_max_clamp: f64,
    pub min_spacing: f64,
}

impl Default for ForestCoefficients {
    fn default() -> Self { Self { base: 0.25, humidity_coef: 0.40, density_coef: 0.10 } }
}

impl Default for WaterCoefficients {
    fn default() -> Self { Self { base: 0.05, humidity_coef: 0.20, energy_coef: 0.10 } }
}

impl Default for IndustryCoefficients {
    fn default() -> Self { Self { base: 0.02, pressure_coef: 0.06 } }
}

impl Default for DensityCoefficients {
    fn default() -> Self {
        Self {
            forest: ForestCoefficients::default(),
            water: WaterCoefficients::default(),
            industry: IndustryCoefficients::default(),
        }
    }
}

impl Default for HiddenParamDefaults {
    fn default() -> Self { Self { pressure: 0.5, humidity: 0.5, density: 0.5, energy: 0.5 } }
}

impl Default for HighwayConfig {
    fn default() -> Self { Self { ratio_default: 0.02, ratio_min_clamp: 0.01, ratio_max_clamp: 1.0, min_spacing: 4.0 } }
}

impl Default for WorldConfig {
    fn default() -> Self {
        Self {
            density: DensityCoefficients::default(),
            hidden_defaults: HiddenParamDefaults::default(),
            highways: HighwayConfig::default(),
        }
    }
}

impl WorldConfig {
    /// Load from the path in env var `GPU_ENGINE_CONFIG` (default
    /// `./gpu.toml`). Missing file → defaults silently. Malformed file →
    /// warn and use defaults; the engine never fails startup over config.
    pub fn load() -> Self {
        let path = std::env::var("GPU_ENGINE_CONFIG").unwrap_or_else(|_| "gpu.toml".to_string());
        Self::load_from(Path::new(&path))
    }

    pub fn load_from(path: &Path) -> Self {
        let contents = match std::fs::read_to_string(path) {
            Ok(s) => s,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                println!("config.load path={} status=absent (using defaults)", path.display());
                return Self::default();
            }
            Err(e) => {
                eprintln!("config.load path={} status=read_error err={} (using defaults)", path.display(), e);
                return Self::default();
            }
        };
        match toml::from_str::<WorldConfig>(&contents) {
            Ok(cfg) => {
                println!("config.load path={} status=ok forest=({},{},{}) water=({},{},{}) industry=({},{}) hidden=({},{},{},{}) highway_ratio_default={}",
                         path.display(),
                         cfg.density.forest.base, cfg.density.forest.humidity_coef, cfg.density.forest.density_coef,
                         cfg.density.water.base, cfg.density.water.humidity_coef, cfg.density.water.energy_coef,
                         cfg.density.industry.base, cfg.density.industry.pressure_coef,
                         cfg.hidden_defaults.pressure, cfg.hidden_defaults.humidity, cfg.hidden_defaults.density, cfg.hidden_defaults.energy,
                         cfg.highways.ratio_default);
                cfg
            }
            Err(e) => {
                eprintln!("config.load path={} status=parse_error err={} (using defaults)", path.display(), e);
                Self::default()
            }
        }
    }
}
