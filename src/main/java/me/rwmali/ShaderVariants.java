package me.rwmali;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-subgroup shader variants for GPU drivers that advertise Vulkan 1.1+ but do
 * not expose the subgroup operations Realistic Water 1.4.1's particle compute
 * shaders rely on (subgroupAdd/Min/Max, subgroupElect, subgroupBroadcastFirst).
 * Typical case: ARM Mali Bifrost drivers (Mali-G52 on a Redmi 15C) that report
 * "Vulkan 1.3" while supportedOperations lacks the arithmetic/ballot bits for
 * the compute stage. shaderc still compiles those shaders (the compile happens
 * on the CPU), and the failure only surfaces at pipeline creation / submit time
 * as "Realistic Water failed: ..." - after which the mod disables the whole
 * simulation, which is why water could not even be placed any more.
 *
 * The fix: 8 shader files (7 compute shaders + common.glsl) rewritten with the
 * exact same physics, where every subgroup construct is replaced by per-invocation
 * atomics (the subgroup ops in these shaders are purely an optimization around
 * atomics - reductions, stream compaction and stats reporting - so the direct
 * atomic form is semantically identical, just with more atomic traffic).
 * They live in THIS addon jar under assets/rw-android-mali/shaders-compat/ and
 * are served through ShadersLoadMixin whenever Shaders.load(name) asks for one
 * of these files.
 *
 * Safety: a variant is only used when the shader the MOD ACTUALLY SHIPS is
 * byte-identical (SHA-256) to the 1.4.1 file the variant was derived from. On
 * any other Realistic Water version (or a future edit of these files) the
 * original source loads unchanged and a warning is logged - the addon can
 * never apply a variant to shader code it was not verified against.
 */
public final class ShaderVariants {

	private static final Logger LOG = LoggerFactory.getLogger("RWMali");

	private static final String MOD_ROOT = "/assets/realistic-water/shaders/";
	private static final String VARIANT_ROOT = "/assets/rw-android-mali/shaders-compat/";

	/**
	 * SHA-256 of the original RW 1.4.1 shader each variant was derived from.
	 * A mismatch here means the gate rejects the variant (fail-safe); it can
	 * never apply a stale one.
	 */
	private static final Map<String, String> EXPECTED_SHA256 = Map.of(
			"common.glsl", "cd0a8230f5d4f4c8c97ec49966b249a1a70ea14abb0dec4db99531a96b41d48e",
			"g2p.comp", "a1d16d5efd87d346c51c478c75ab514562554e9a0158fdacbcb02e9ad182c5c3",
			"p2g.comp", "dcc6b13c504c6082ba6bdc0de1874875cb5aac785c81b91e213ae26897371501",
			"mpm_p2g.comp", "91c30cf6e43e6e65ee99d3fc5a40e10581f8330296b72624fbb9366d076ff490",
			"mpm_g2p.comp", "5a658127e71ecb02f0d4ba74adb7635606e2abecaa49f1f5e6bcb5ea195f6d97",
			"ww_update.comp", "2ada8cfd7f85ac9d37550c40d00c7668209e91c5dfcb8c01ff54b2c4f177b470",
			"steam_step.comp", "2d8457a31c685c47a7191a29cffb8a07825971bfefad224609d7f5b4c30a8d9d",
			"leaf_step.comp", "07059476b7abe6475bbde8e845d5df2a8d6186ea5f670dcc6ec17c0439832f53");

	/** Variant sources already handed out (name -> text); a hit skips all checks. */
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	/** Names whose original did not match the expected hash (or could not be read). */
	private static final Set<String> REJECTED = ConcurrentHashMap.newKeySet();

	/** Session-wide decision, computed once (see active()); null until then. */
	private static volatile Boolean active;

	private ShaderVariants() {
	}

	/** True when the no-subgroup variants should be served. Never throws. */
	static boolean active() {
		Boolean cached = active;
		if (cached != null) {
			return cached;
		}
		synchronized (ShaderVariants.class) {
			cached = active;
			if (cached != null) {
				return cached;
			}
			active = computeActive();
			return active;
		}
	}

	private static boolean computeActive() {
		try {
			if (!MaliCompat.isAndroid()) {
				// The addon is a mobile add-on; desktop always runs the mod's own shaders.
				MaliReport.note("subgroup-compat", "OFF (desktop - addon dormant)");
				return false;
			}
			int mode = MaliSettings.maliSubgroupCompat; // 0=auto 1=force on 2=force off
			if (mode == 2) {
				MaliReport.note("subgroup-compat", "OFF (forced by maliSubgroupCompat=2)");
				return false;
			}
			if (mode == 1) {
				LOG.info("Subgroup compatibility shaders forced ON (maliSubgroupCompat=1)");
				MaliReport.note("subgroup-compat", "ON (forced by maliSubgroupCompat=1)");
				return true;
			}
			MaliProbe.Result probe = MaliCompat.probe();
			if (probe == null || !probe.hasDevice()) {
				// No Vulkan device at all: no water shaders will ever load; keep the
				// decision cheap and inert.
				MaliReport.note("subgroup-compat", "OFF (no Vulkan device to test)");
				return false;
			}
			if (probe.subgroupComputeFull()) {
				LOG.info("Driver exposes full compute subgroup ops ({}). Realistic Water's own shaders run unchanged.",
								probe.subgroupLine());
				MaliReport.note("subgroup-compat", "OFF (driver has full compute subgroups)");
				MaliReport.note("subgroups", probe.subgroupLine());
				return false;
			}
			LOG.info("Driver lacks the compute subgroup ops Realistic Water's shaders use ({}).", probe.subgroupLine());
			LOG.info("Serving the Mali addon's no-subgroup shader variants for the water simulation - identical"
					+ " physics, group ops replaced by plain atomics (a bit slower on weak GPUs, but the water -"
					+ " and placing water - works). Toggle: /water set maliSubgroupCompat 0|1|2 (0=auto 1=force on"
					+ " 2=off), restart to apply.");
			MaliReport.note("subgroup-compat", "ON (driver lacks compute subgroup ops)");
			MaliReport.note("subgroups", probe.subgroupLine());
			return true;
		} catch (Throwable t) {
			LOG.warn("Subgroup-compat decision failed ({}); serving the mod's own shaders", t.toString());
			MaliReport.note("subgroup-compat", "OFF (decision error: " + t + ")");
			return false;
		}
	}

	/**
	 * Variant source for a shader name, or null when this shader should load
	 * unchanged. Called from ShadersLoadMixin at the HEAD of Shaders.load(),
	 * so both the mod's own compiles and the bridge's SPIR-V cache key (which
	 * hashes the same load() output) automatically see the variant text - cache
	 * entries of subgroup and no-subgroup builds can never collide.
	 */
	public static String forShader(String name) {
		try {
			if (name == null || !EXPECTED_SHA256.containsKey(name)) {
				return null;
			}
			String cached = CACHE.get(name);
			if (cached != null) {
				return cached;
			}
			if (REJECTED.contains(name)) {
				return null;
			}
			if (!active()) {
				return null;
			}
			byte[] original = read(MOD_ROOT + name);
			if (original == null) {
				reject(name, "original shader not readable");
				return null;
			}
			String hash = sha256(original);
			if (!EXPECTED_SHA256.get(name).equals(hash)) {
				// Fail-safe: the installed RW ships a different file than the variant
				// was derived from (different RW version or an edited shader pack).
				reject(name, "shader differs from the verified RW 1.4.1 original (sha256 " + hash + ")");
				return null;
			}
			String variant = new String(read(VARIANT_ROOT + name), StandardCharsets.UTF_8);
			if (variant.isBlank()) {
				reject(name, "variant resource missing in the addon jar");
				return null;
			}
			CACHE.put(name, variant);
			MaliReport.note("variant " + name, "APPLIED (original hash verified)");
			return variant;
		} catch (Throwable t) {
			LOG.warn("Could not serve the compat variant for {}: {} - using the mod's own shader", name, t.toString());
			return null;
		}
	}

	private static void reject(String name, String why) {
		if (REJECTED.add(name)) {
			LOG.warn("No-subgroup variant for {} NOT applied: {}. The mod's own shader loads instead.", name, why);
			MaliReport.note("variant " + name, "NOT APPLIED (" + why + ")");
		}
	}

	private static byte[] read(String resource) {
		try (InputStream in = ShaderVariants.class.getResourceAsStream(resource)) {
			return in == null ? null : in.readAllBytes();
		} catch (Throwable t) {
			return null;
		}
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (Throwable t) {
			return "unavailable";
		}
	}
}
