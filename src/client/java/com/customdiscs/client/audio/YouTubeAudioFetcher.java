package com.customdiscs.client.audio;

import com.customdiscs.audio.WebmOpusDemuxer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches audio from a YouTube URL on the client side and converts it into a
 * ready-to-upload {@link AudioFileDecoder.EncodedTrack}.
 *
 * Strategy:
 * 1. If yt-dlp is installed ({@code <gameDir>/customdiscs_tools/} or system PATH),
 *    tries downloading WebM/Opus (first with android_vr/mweb/default fallback so it
 *    works even without Node/Deno JS runtime, and without ffmpeg).
 * 2. Falls back to the built-in pure-Java YouTube InnerTube client.
 * 3. Logs complete diagnostics to the Minecraft console for easy troubleshooting.
 */
public final class YouTubeAudioFetcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-youtube");

	private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
			"(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|live/|v/))([A-Za-z0-9_-]{11})"
	);
	private static final Pattern RAW_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private YouTubeAudioFetcher() {
	}

	public static Optional<String> extractVideoId(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String trimmed = input.trim();
		if (trimmed.isEmpty()) {
			return Optional.empty();
		}
		if (RAW_ID_PATTERN.matcher(trimmed).matches()) {
			return Optional.of(trimmed);
		}
		Matcher matcher = VIDEO_ID_PATTERN.matcher(trimmed);
		if (matcher.find()) {
			return Optional.of(matcher.group(1));
		}
		return Optional.empty();
	}

	public static AudioFileDecoder.EncodedTrack fetchAndEncode(
			String urlOrId,
			Path gameDir,
			int maxDurationSeconds,
			int maxEncodedBytes,
			int bitrateBps
	) throws IOException {
		String videoId = extractVideoId(urlOrId)
				.orElseThrow(() -> new IOException("Invalid YouTube link"));

		Optional<Path> ytDlp = findExecutable(gameDir, "yt-dlp");
		IOException ytDlpError = null;

		if (ytDlp.isPresent()) {
			LOGGER.info("Fetching YouTube video {} using yt-dlp at {}", videoId, ytDlp.get());
			try {
				return fetchViaYtDlp(ytDlp.get(), gameDir, videoId, maxDurationSeconds, maxEncodedBytes, bitrateBps);
			} catch (IOException e) {
				ytDlpError = e;
				LOGGER.warn("yt-dlp failed for {}: {}. Falling back to built-in InnerTube client...", videoId, e.getMessage());
			}
		} else {
			LOGGER.info("yt-dlp not found in PATH or {}/customdiscs_tools; trying built-in InnerTube client for {}", gameDir, videoId);
		}

		try {
			return fetchViaInnerTube(videoId, maxDurationSeconds, maxEncodedBytes, bitrateBps);
		} catch (IOException innerTubeErr) {
			LOGGER.error("Built-in InnerTube client failed for {}: {}", videoId, innerTubeErr.getMessage());
			if (ytDlpError != null) {
				throw new IOException(ytDlpError.getMessage(), ytDlpError);
			}
			throw new IOException(
					innerTubeErr.getMessage() + " — Install yt-dlp (winget install yt-dlp.yt-dlp) or put yt-dlp.exe in .minecraft/customdiscs_tools/",
					innerTubeErr
			);
		}
	}

	private static Optional<Path> findExecutable(Path gameDir, String baseName) {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String exeName = windows ? (baseName + ".exe") : baseName;

		// 1. Mod's local tools folder: <gameDir>/customdiscs_tools/
		if (gameDir != null) {
			Path local = gameDir.resolve("customdiscs_tools").resolve(exeName).toAbsolutePath().normalize();
			if (Files.isRegularFile(local)) {
				if (!windows && !Files.isExecutable(local)) {
					LOGGER.warn("Found {} but it is not executable! Run: chmod +x \"{}\"", local, local);
				} else {
					return Optional.of(local);
				}
			}
		}

		// 2. System PATH
		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
				if (dir == null || dir.isBlank()) continue;
				try {
					Path candidate = Path.of(dir.trim(), exeName);
					if (Files.isRegularFile(candidate)) {
						return Optional.of(candidate);
					}
				} catch (Exception ignored) {
				}
			}
		}

		// 3. Common OS-specific locations (desktop launchers often omit ~/.local/bin or /usr/local/bin from PATH)
		String userHome = System.getProperty("user.home");
		if (windows) {
			String localAppData = System.getenv("LOCALAPPDATA");
			if (localAppData != null) {
				Path winget = Path.of(localAppData, "Microsoft", "WinGet", "Links", exeName);
				if (Files.isRegularFile(winget)) {
					return Optional.of(winget);
				}
			}
			if (userHome != null) {
				Path scoop = Path.of(userHome, "scoop", "shims", exeName);
				if (Files.isRegularFile(scoop)) {
					return Optional.of(scoop);
				}
			}
			Path choco = Path.of("C:\\ProgramData\\chocolatey\\bin", exeName);
			if (Files.isRegularFile(choco)) {
				return Optional.of(choco);
			}
		} else {
			List<Path> unixPaths = new ArrayList<>();
			if (userHome != null) {
				unixPaths.add(Path.of(userHome, ".local", "bin", exeName));
				unixPaths.add(Path.of(userHome, ".nix-profile", "bin", exeName));
			}
			unixPaths.add(Path.of("/usr/local/bin", exeName));
			unixPaths.add(Path.of("/usr/bin", exeName));
			unixPaths.add(Path.of("/snap/bin", exeName));
			unixPaths.add(Path.of("/opt/homebrew/bin", exeName));
			for (Path candidate : unixPaths) {
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
					return Optional.of(candidate);
				}
			}
		}

		return Optional.empty();
	}

	private static AudioFileDecoder.EncodedTrack fetchViaYtDlp(
			Path ytDlpExe,
			Path gameDir,
			String videoId,
			int maxDurationSeconds,
			int maxEncodedBytes,
			int bitrateBps
	) throws IOException {
		String canonicalUrl = "https://www.youtube.com/watch?v=" + videoId;
		Path tempDir = Files.createTempDirectory("customdiscs-yt-");

		// Two strategies:
		// 1) android_vr / mweb client: serves native WebM/Opus without needing Node/Deno JS runtime
		// 2) yt-dlp default client (uses system JS runtime if installed)
		List<List<String>> attempts = List.of(
				List.of(
						ytDlpExe.toString(),
						"--no-playlist",
						"--extractor-args", "youtube:player_client=android_vr,mweb,default",
						"-f", "bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio/best",
						"--max-filesize", "60M",
						"--write-info-json",
						"-o", tempDir.resolve("audio.%(ext)s").toString(),
						canonicalUrl
				),
				List.of(
						ytDlpExe.toString(),
						"--no-playlist",
						"-f", "bestaudio[ext=webm]/bestaudio[acodec=opus]/bestaudio/best",
						"--max-filesize", "60M",
						"--write-info-json",
						"-o", tempDir.resolve("audio.%(ext)s").toString(),
						canonicalUrl
				)
		);

		try {
			IOException lastErr = null;
			for (int i = 0; i < attempts.size(); i++) {
				cleanDirectory(tempDir);
				List<String> cmd = attempts.get(i);
				LOGGER.info("Running yt-dlp attempt {}/{}: {}", i + 1, attempts.size(), String.join(" ", cmd));
				try {
					ProcessBuilder pb = new ProcessBuilder(cmd);
					pb.redirectErrorStream(true);
					runProcess(pb, 120);
					lastErr = null;
					break;
				} catch (IOException e) {
					lastErr = e;
					LOGGER.warn("yt-dlp attempt {}/{} failed: {}", i + 1, attempts.size(), e.getMessage());
				}
			}
			if (lastErr != null) {
				throw lastErr;
			}

			// Read metadata from .info.json if written
			String title = "YouTube Track";
			String artist = "";
			Optional<Path> infoJson = findFileWithSuffix(tempDir, ".info.json");
			if (infoJson.isPresent()) {
				try {
					JsonObject metaJson = JsonParser.parseString(Files.readString(infoJson.get(), StandardCharsets.UTF_8)).getAsJsonObject();
					title = getJsonString(metaJson, "track", getJsonString(metaJson, "title", "YouTube Track"));
					artist = getJsonString(metaJson, "artist", getJsonString(metaJson, "uploader", ""));
					if (metaJson.has("duration") && !metaJson.get("duration").isJsonNull()) {
						int dur = (int) Math.ceil(metaJson.get("duration").getAsDouble());
						if (dur > maxDurationSeconds) {
							throw new IOException("Video exceeds server limit (" + dur + "s > " + maxDurationSeconds + "s)");
						}
					}
				} catch (IOException e) {
					throw e;
				} catch (Exception e) {
					LOGGER.warn("Could not parse yt-dlp info.json: {}", e.getMessage());
				}
			}

			Path downloaded = findDownloadedAudioFile(tempDir)
					.orElseThrow(() -> new IOException("yt-dlp produced no audio file"));

			String fileName = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
			LOGGER.info("yt-dlp downloaded {} ({} bytes)", fileName, Files.size(downloaded));

			if (!AudioFileDecoder.isSupportedExtension(downloaded)) {
				Path wavFile = tempDir.resolve("converted.wav");
				Optional<Path> ffmpeg = findExecutable(gameDir, "ffmpeg");
				if (ffmpeg.isPresent() && tryFfmpegToWav(ffmpeg.get(), downloaded, wavFile)) {
					downloaded = wavFile;
				} else {
					throw new IOException("Format '" + fileName + "' needs ffmpeg (or update yt-dlp: yt-dlp -U)");
				}
			}

			AudioFileDecoder.EncodedTrack raw = AudioFileDecoder.decodeAndEncode(
					downloaded, maxDurationSeconds, maxEncodedBytes, bitrateBps
			);
			return new AudioFileDecoder.EncodedTrack(
					raw.trackId(),
					raw.cdopBytes(),
					raw.durationTicks(),
					cleanMeta(title, raw.suggestedTitle()),
					cleanMeta(artist, raw.suggestedArtist())
			);
		} finally {
			deleteQuietly(tempDir);
		}
	}

	private static boolean tryFfmpegToWav(Path ffmpegExe, Path input, Path outputWav) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					ffmpegExe.toString(), "-y", "-i", input.toString(),
					"-vn", "-ac", "1", "-ar", "48000", "-f", "wav", outputWav.toString()
			);
			pb.redirectErrorStream(true);
			runProcess(pb, 60);
			return Files.isRegularFile(outputWav) && Files.size(outputWav) > 44;
		} catch (Exception e) {
			LOGGER.warn("ffmpeg conversion failed: {}", e.getMessage());
			return false;
		}
	}

	private record InnerTubeProfile(String clientName, String clientVersion, String userAgent, String extraClientJson) {
	}

	private static final List<InnerTubeProfile> INNERTUBE_PROFILES = List.of(
			new InnerTubeProfile(
					"ANDROID_VR",
					"1.60.19",
					"com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
					"\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12L\""
			),
			new InnerTubeProfile(
					"IOS",
					"20.10.4",
					"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
					"\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.3.2.22D82\""
			)
	);

	private static AudioFileDecoder.EncodedTrack fetchViaInnerTube(
			String videoId,
			int maxDurationSeconds,
			int maxEncodedBytes,
			int bitrateBps
	) throws IOException {
		IOException lastError = new IOException("Could not resolve YouTube audio stream");

		for (InnerTubeProfile profile : INNERTUBE_PROFILES) {
			try {
				JsonObject response = callInnerTubePlayer(videoId, profile);
				JsonObject playability = response.getAsJsonObject("playabilityStatus");
				String status = getJsonString(playability, "status", "");
				if (!"OK".equalsIgnoreCase(status)) {
					String reason = getJsonString(playability, "reason", "Video unavailable (" + status + ")");
					LOGGER.warn("InnerTube ({}) playability status={}: {}", profile.clientName(), status, reason);
					lastError = new IOException(reason);
					continue;
				}

				JsonObject details = response.getAsJsonObject("videoDetails");
				String title = getJsonString(details, "title", "YouTube Track");
				String author = getJsonString(details, "author", "");
				int lengthSeconds = 0;
				if (details != null && details.has("lengthSeconds")) {
					try {
						lengthSeconds = Integer.parseInt(details.get("lengthSeconds").getAsString());
					} catch (NumberFormatException ignored) {
					}
				}
				if (lengthSeconds > maxDurationSeconds) {
					throw new IOException("Video exceeds server limit (" + lengthSeconds + "s > " + maxDurationSeconds + "s)");
				}

				JsonObject streamingData = response.getAsJsonObject("streamingData");
				String opusUrl = findBestWebmOpusUrl(streamingData);
				if (opusUrl == null) {
					LOGGER.warn("InnerTube ({}) returned no WebM/Opus stream URL", profile.clientName());
					lastError = new IOException("No WebM/Opus stream returned by YouTube");
					continue;
				}

				byte[] webmBytes = downloadBytes(opusUrl, profile.userAgent(), AudioFileDecoder.MAX_RAW_INPUT_BYTES);
				short[] mono48k = WebmOpusDemuxer.decodeToMono48k(webmBytes, maxDurationSeconds);
				return AudioFileDecoder.encodeMono48kToTrack(
						mono48k,
						maxDurationSeconds,
						maxEncodedBytes,
						bitrateBps,
						cleanMeta(title, "YouTube Track"),
						cleanMeta(author, "")
				);
			} catch (IOException e) {
				if (e.getMessage() != null && e.getMessage().contains("exceeds server limit")) {
					throw e;
				}
				lastError = e;
			}
		}

		throw lastError;
	}

	private static JsonObject callInnerTubePlayer(String videoId, InnerTubeProfile profile) throws IOException {
		String body = "{"
				+ "\"videoId\":\"" + videoId + "\","
				+ "\"contentCheckOk\":true,"
				+ "\"racyCheckOk\":true,"
				+ "\"context\":{\"client\":{"
				+ "\"clientName\":\"" + profile.clientName() + "\","
				+ "\"clientVersion\":\"" + profile.clientVersion() + "\","
				+ profile.extraClientJson() + ","
				+ "\"hl\":\"en\",\"gl\":\"US\""
				+ "}}}";

		HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false"))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("User-Agent", profile.userAgent())
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

		try {
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() != 200) {
				throw new IOException("YouTube API HTTP " + resp.statusCode());
			}
			return JsonParser.parseString(resp.body()).getAsJsonObject();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("YouTube request interrupted", e);
		}
	}

	private static String findBestWebmOpusUrl(JsonObject streamingData) {
		if (streamingData == null || !streamingData.has("adaptiveFormats")) {
			return null;
		}
		JsonArray formats = streamingData.getAsJsonArray("adaptiveFormats");
		List<JsonObject> opusFormats = new ArrayList<>();
		for (JsonElement elem : formats) {
			if (!elem.isJsonObject()) continue;
			JsonObject fmt = elem.getAsJsonObject();
			if (!fmt.has("url")) continue;
			String mime = getJsonString(fmt, "mimeType", "").toLowerCase(Locale.ROOT);
			if (mime.contains("audio/webm") && mime.contains("opus")) {
				opusFormats.add(fmt);
			}
		}
		if (opusFormats.isEmpty()) {
			return null;
		}
		opusFormats.sort(Comparator.comparingInt((JsonObject f) -> f.has("bitrate") ? f.get("bitrate").getAsInt() : 0).reversed());
		return opusFormats.getFirst().get("url").getAsString();
	}

	private static byte[] downloadBytes(String url, String userAgent, long maxBytes) throws IOException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", userAgent)
				.GET()
				.build();
		try {
			HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				throw new IOException("Audio download HTTP " + resp.statusCode());
			}
			try (InputStream in = resp.body()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
				byte[] buf = new byte[16_384];
				long total = 0;
				int read;
				while ((read = in.read(buf)) != -1) {
					total += read;
					if (total > maxBytes) {
						throw new IOException("YouTube audio stream exceeded max download size");
					}
					out.write(buf, 0, read);
				}
				return out.toByteArray();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("YouTube download interrupted", e);
		}
	}

	private static String runProcess(ProcessBuilder pb, int timeoutSeconds) throws IOException {
		Process proc = pb.start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> {
			try (InputStream in = proc.getInputStream()) {
				in.transferTo(output);
			} catch (IOException ignored) {
			}
		}, "CustomDiscs-ProcReader");
		reader.setDaemon(true);
		reader.start();

		try {
			boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				proc.destroyForcibly();
				throw new IOException("yt-dlp timed out after " + timeoutSeconds + "s");
			}
			reader.join(2000);
		} catch (InterruptedException e) {
			proc.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("yt-dlp interrupted", e);
		}

		String text = output.toString(StandardCharsets.UTF_8);
		if (proc.exitValue() != 0) {
			LOGGER.error("Process exited with code {}. Full output:\n{}", proc.exitValue(), text);
			String errorLine = text.lines()
					.map(String::trim)
					.filter(l -> l.startsWith("ERROR:"))
					.reduce((a, b) -> b)
					.orElseGet(() -> text.lines()
							.map(String::trim)
							.filter(l -> !l.isBlank())
							.reduce((a, b) -> b)
							.orElse("exit code " + proc.exitValue()));
			if (errorLine.startsWith("ERROR:")) {
				errorLine = errorLine.substring("ERROR:".length()).trim();
			}
			if (errorLine.length() > 160) {
				errorLine = errorLine.substring(0, 160);
			}
			throw new IOException(errorLine);
		}
		return text;
	}

	private static String getJsonString(JsonObject obj, String key, String fallback) {
		if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
			String val = obj.get(key).getAsString().trim();
			if (!val.isEmpty()) {
				return val;
			}
		}
		return fallback;
	}

	private static String cleanMeta(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
		return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
	}

	private static Optional<Path> findFileWithSuffix(Path dir, String suffix) throws IOException {
		try (var stream = Files.list(dir)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
					.findFirst();
		}
	}

	private static Optional<Path> findDownloadedAudioFile(Path dir) throws IOException {
		try (var stream = Files.list(dir)) {
			return stream
					.filter(Files::isRegularFile)
					.filter(p -> {
						String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
						return !name.endsWith(".json") && !name.endsWith(".part") && !name.endsWith(".ytdl");
					})
					.findFirst();
		}
	}

	private static void cleanDirectory(Path dir) {
		try (var stream = Files.list(dir)) {
			stream.forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static void deleteQuietly(Path dir) {
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}
}
