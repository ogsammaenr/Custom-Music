package com.customdiscs.client.screen;

import com.customdiscs.client.CustomDiscsClient;
import com.customdiscs.client.audio.AudioFileDecoder;
import com.customdiscs.client.audio.YouTubeAudioFetcher;
import com.customdiscs.client.network.ClientUploadManager;
import com.customdiscs.menu.DiscBurnerMenu;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-game Disc Burner UI with:
 * - Direct YouTube link paste & auto-burn
 * - Drag-and-drop (.mp3 / .wav / .ogg / .webm) support
 * - Editable title & artist fields, 10 label colors, and multiplayer upload progress
 */
public class DiscBurnerScreen extends AbstractContainerScreen<DiscBurnerMenu> implements ClientUploadManager.UploadListener {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-screen");
	private static final int UI_WIDTH = 220;
	private static final int UI_HEIGHT = 240;

	private static final int DROP_X = 42;
	private static final int DROP_Y = 41;
	private static final int DROP_W = 166;
	private static final int DROP_H = 14;

	private static final int[] PRESET_COLORS = {
			0x38D2F5, // Cyan
			0xFF4D6D, // Crimson
			0x3CE089, // Emerald
			0xFFD166, // Gold
			0xA77BFF, // Violet
			0xFF8C42, // Orange
			0x9EF01A, // Lime
			0x4EA8DE, // Sky
			0xF78FB3, // Pink
			0xF2F4F8  // White
	};

	private static final int SWATCH_START_X = 12;
	private static final int SWATCH_Y = 104;
	private static final int SWATCH_SIZE = 12;
	private static final int SWATCH_GAP = 3;

	private static final ExecutorService WORKER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "CustomDiscs-AudioWorker");
		t.setDaemon(true);
		return t;
	});

	private EditBox urlBox;
	private Button fetchButton;
	private EditBox titleBox;
	private EditBox artistBox;
	private Button burnButton;

	private AudioFileDecoder.EncodedTrack encodedTrack;
	private String sourceSummary = null;
	private int selectedColorIndex = 0;
	private boolean busy = false;
	private boolean uploading = false;
	private int uploadProgress = 0;
	private Component statusMessage = Component.translatable("gui.customdiscs.status.idle");
	private int statusColor = 0xFFB8C4D4;

	public DiscBurnerScreen(DiscBurnerMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title, UI_WIDTH, UI_HEIGHT);
		this.titleLabelX = 10;
		this.titleLabelY = 6;
		this.inventoryLabelX = DiscBurnerMenu.INV_START_X;
		this.inventoryLabelY = DiscBurnerMenu.INV_START_Y - 11;
	}

	@Override
	protected void init() {
		super.init();
		CustomDiscsClient.UPLOAD_MANAGER.setListener(this);

		String prevUrl = this.urlBox != null ? this.urlBox.getValue() : "";
		String prevTitle = this.titleBox != null ? this.titleBox.getValue() : "";
		String prevArtist = this.artistBox != null ? this.artistBox.getValue() : "";

		this.urlBox = new EditBox(this.font, this.leftPos + 42, this.topPos + 21, 118, 16, Component.translatable("gui.customdiscs.youtube_hint"));
		this.urlBox.setMaxLength(256);
		this.urlBox.setValue(prevUrl);
		this.urlBox.setHint(Component.translatable("gui.customdiscs.youtube_hint"));
		this.addRenderableWidget(this.urlBox);

		this.fetchButton = Button.builder(Component.translatable("gui.customdiscs.fetch_button"), btn -> onFetchYouTubePressed())
				.bounds(this.leftPos + 163, this.topPos + 20, 45, 18)
				.build();
		this.addRenderableWidget(this.fetchButton);

		this.titleBox = new EditBox(this.font, this.leftPos + 12, this.topPos + 67, 94, 16, Component.translatable("gui.customdiscs.title_label"));
		this.titleBox.setMaxLength(64);
		this.titleBox.setValue(prevTitle);
		this.titleBox.setHint(Component.translatable("gui.customdiscs.title_label"));
		this.addRenderableWidget(this.titleBox);

		this.artistBox = new EditBox(this.font, this.leftPos + 114, this.topPos + 67, 94, 16, Component.translatable("gui.customdiscs.artist_label"));
		this.artistBox.setMaxLength(64);
		this.artistBox.setValue(prevArtist);
		this.artistBox.setHint(Component.translatable("gui.customdiscs.artist_label"));
		this.addRenderableWidget(this.artistBox);

		this.burnButton = Button.builder(Component.translatable("gui.customdiscs.burn_button"), btn -> onBurnPressed())
				.bounds(this.leftPos + 132, this.topPos + 119, 76, 18)
				.build();
		this.addRenderableWidget(this.burnButton);

		this.setInitialFocus(this.urlBox);
		updateButtonState();
	}

	@Override
	public void removed() {
		super.removed();
		CustomDiscsClient.UPLOAD_MANAGER.setListener(null);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		updateButtonState();
	}

	private void updateButtonState() {
		if (this.fetchButton != null) {
			boolean hasUrl = this.urlBox != null && !this.urlBox.getValue().trim().isEmpty();
			this.fetchButton.active = !busy && !uploading && hasUrl;
		}
		if (this.burnButton != null) {
			this.burnButton.active = !busy && !uploading && encodedTrack != null && this.menu.hasBurnableDisc();
		}
	}

	private void onFetchYouTubePressed() {
		if (busy || uploading || this.urlBox == null) {
			return;
		}
		String rawUrl = this.urlBox.getValue().trim();
		if (YouTubeAudioFetcher.extractVideoId(rawUrl).isEmpty()) {
			this.statusMessage = Component.translatable("gui.customdiscs.status.invalid_youtube");
			this.statusColor = 0xFFFF6B6B;
			return;
		}

		this.busy = true;
		this.encodedTrack = null;
		this.uploadProgress = 0;
		this.sourceSummary = "YouTube";
		this.statusMessage = Component.translatable("gui.customdiscs.status.fetching_youtube");
		this.statusColor = 0xFF38D2F5;
		updateButtonState();

		ClientUploadManager uploadMgr = CustomDiscsClient.UPLOAD_MANAGER;
		int maxSec = uploadMgr.maxDurationSeconds();
		int maxBytes = uploadMgr.maxFileSizeBytes();
		int bitrate = uploadMgr.opusBitrateBps();
		Path gameDir = FabricLoader.getInstance().getGameDir();

		WORKER_EXECUTOR.submit(() -> {
			try {
				AudioFileDecoder.EncodedTrack track = YouTubeAudioFetcher.fetchAndEncode(rawUrl, gameDir, maxSec, maxBytes, bitrate);
				this.minecraft.execute(() -> {
					this.busy = false;
					this.encodedTrack = track;
					if (this.titleBox != null) {
						this.titleBox.setValue(track.suggestedTitle());
					}
					if (this.artistBox != null) {
						this.artistBox.setValue(track.suggestedArtist());
					}
					String dur = formatTicks(track.durationTicks());
					int kb = Math.max(1, track.cdopBytes().length / 1024);
					this.sourceSummary = dur + "  |  " + kb + " KB Opus";

					// Auto-burn right away if the player already placed a disc in the slot!
					if (this.menu.hasBurnableDisc()) {
						onBurnPressed();
					} else {
						this.statusMessage = Component.translatable("gui.customdiscs.status.ready", dur, kb);
						this.statusColor = 0xFF3CE089;
						updateButtonState();
					}
				});
			} catch (Exception e) {
				LOGGER.error("Failed to fetch YouTube audio for URL '{}'", rawUrl, e);
				String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				this.minecraft.execute(() -> {
					this.busy = false;
					this.encodedTrack = null;
					this.sourceSummary = null;
					this.statusMessage = Component.translatable("gui.customdiscs.status.error", msg);
					this.statusColor = 0xFFFF6B6B;
					updateButtonState();
				});
			}
		});
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths == null || paths.isEmpty() || busy || uploading) {
			return;
		}
		Path dropped = paths.getFirst();
		if (!AudioFileDecoder.isSupportedExtension(dropped)) {
			this.statusMessage = Component.translatable("gui.customdiscs.status.unsupported");
			this.statusColor = 0xFFFF6B6B;
			return;
		}

		this.sourceSummary = dropped.getFileName().toString();
		this.encodedTrack = null;
		this.busy = true;
		this.uploadProgress = 0;
		this.statusMessage = Component.translatable("gui.customdiscs.status.encoding");
		this.statusColor = 0xFF38D2F5;
		updateButtonState();

		ClientUploadManager uploadMgr = CustomDiscsClient.UPLOAD_MANAGER;
		int maxSec = uploadMgr.maxDurationSeconds();
		int maxBytes = uploadMgr.maxFileSizeBytes();
		int bitrate = uploadMgr.opusBitrateBps();

		WORKER_EXECUTOR.submit(() -> {
			try {
				AudioFileDecoder.EncodedTrack track = AudioFileDecoder.decodeAndEncode(dropped, maxSec, maxBytes, bitrate);
				this.minecraft.execute(() -> {
					this.busy = false;
					this.encodedTrack = track;
					if (this.titleBox != null) {
						this.titleBox.setValue(track.suggestedTitle());
					}
					if (this.artistBox != null) {
						this.artistBox.setValue(track.suggestedArtist());
					}
					String dur = formatTicks(track.durationTicks());
					int kb = Math.max(1, track.cdopBytes().length / 1024);
					this.sourceSummary = dur + "  |  " + kb + " KB Opus";
					this.statusMessage = Component.translatable("gui.customdiscs.status.ready", dur, kb);
					this.statusColor = 0xFF3CE089;
					updateButtonState();
				});
			} catch (Exception e) {
				LOGGER.error("Failed to decode dropped audio file '{}'", dropped, e);
				String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				this.minecraft.execute(() -> {
					this.busy = false;
					this.encodedTrack = null;
					this.sourceSummary = null;
					this.statusMessage = Component.translatable("gui.customdiscs.status.error", msg);
					this.statusColor = 0xFFFF6B6B;
					updateButtonState();
				});
			}
		});
	}

	private void onBurnPressed() {
		if (busy || uploading || encodedTrack == null) {
			return;
		}
		if (!this.menu.hasBurnableDisc()) {
			this.statusMessage = Component.translatable("gui.customdiscs.status.insert_disc");
			this.statusColor = 0xFFFFD166;
			return;
		}

		String title = this.titleBox.getValue().trim();
		if (title.isEmpty()) {
			title = encodedTrack.suggestedTitle();
		}
		String artist = this.artistBox.getValue().trim();
		int color = PRESET_COLORS[selectedColorIndex];

		this.uploading = true;
		this.uploadProgress = 0;
		this.statusMessage = Component.translatable("gui.customdiscs.status.uploading", 0);
		this.statusColor = 0xFF38D2F5;
		updateButtonState();

		CustomDiscsClient.UPLOAD_MANAGER.startUpload(encodedTrack, title, artist, color, this.menu.getBlockPos());
	}

	@Override
	public void onUploadProgress(int percent) {
		this.uploadProgress = Math.max(0, Math.min(100, percent));
		this.statusMessage = Component.translatable("gui.customdiscs.status.uploading", this.uploadProgress);
		this.statusColor = 0xFF38D2F5;
	}

	@Override
	public void onUploadComplete() {
		this.uploading = false;
		this.uploadProgress = 100;
		this.statusMessage = Component.translatable("gui.customdiscs.status.complete");
		this.statusColor = 0xFF3CE089;
		updateButtonState();
	}

	@Override
	public void onUploadError(String errorMessage) {
		this.uploading = false;
		this.uploadProgress = 0;
		this.statusMessage = Component.translatable("gui.customdiscs.status.error", errorMessage);
		this.statusColor = 0xFFFF6B6B;
		updateButtonState();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE) {
			return super.keyPressed(event);
		}
		if (this.urlBox != null && this.urlBox.isFocused()) {
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				onFetchYouTubePressed();
				return true;
			}
			return this.urlBox.keyPressed(event) || this.urlBox.canConsumeInput();
		}
		if (this.titleBox != null && this.titleBox.isFocused()) {
			return this.titleBox.keyPressed(event) || this.titleBox.canConsumeInput();
		}
		if (this.artistBox != null && this.artistBox.isFocused()) {
			return this.artistBox.keyPressed(event) || this.artistBox.canConsumeInput();
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		for (int i = 0; i < PRESET_COLORS.length; i++) {
			int sx = this.leftPos + SWATCH_START_X + i * (SWATCH_SIZE + SWATCH_GAP);
			int sy = this.topPos + SWATCH_Y;
			if (mx >= sx && mx <= sx + SWATCH_SIZE && my >= sy && my <= sy + SWATCH_SIZE) {
				this.selectedColorIndex = i;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);

		int x = this.leftPos;
		int y = this.topPos;

		// Outer studio panel
		graphics.fill(x, y, x + UI_WIDTH, y + UI_HEIGHT, 0xFF1B1F29);
		graphics.outline(x, y, UI_WIDTH, UI_HEIGHT, 0xFF3B4559);
		graphics.fill(x + 1, y + 1, x + UI_WIDTH - 1, y + 17, 0xFF232938);

		// Disc input slot box
		int slotX = x + DiscBurnerMenu.DISC_SLOT_X - 2;
		int slotY = y + DiscBurnerMenu.DISC_SLOT_Y - 2;
		int slotBorder = this.menu.hasBurnableDisc() ? 0xFF3CE089 : 0xFF56637A;
		graphics.fill(slotX, slotY, slotX + 20, slotY + 20, 0xFF10131A);
		graphics.outline(slotX, slotY, 20, 20, slotBorder);

		// Compact info / drag-and-drop strip below YouTube URL bar
		int dx = x + DROP_X;
		int dy = y + DROP_Y;
		int dropBg = (encodedTrack != null) ? 0xFF182A2B : 0xFF141821;
		int dropBorder = busy ? 0xFFFFD166 : (encodedTrack != null ? 0xFF3CE089 : 0xFF303B4F);
		graphics.fill(dx, dy, dx + DROP_W, dy + DROP_H, dropBg);
		graphics.outline(dx, dy, DROP_W, DROP_H, dropBorder);

		if (sourceSummary == null) {
			graphics.centeredText(this.font, Component.translatable("gui.customdiscs.drop_hint_compact"), dx + DROP_W / 2, dy + 3, 0xFF8AA4BE);
		} else {
			String shortInfo = sourceSummary.length() > 26 ? sourceSummary.substring(0, 23) + "..." : sourceSummary;
			int infoColor = (encodedTrack != null) ? 0xFF3CE089 : 0xFFFFD166;
			graphics.centeredText(this.font, shortInfo, dx + DROP_W / 2, dy + 3, infoColor);
		}

		// Field labels
		graphics.text(this.font, Component.translatable("gui.customdiscs.title_label"), x + 12, y + 58, 0xFFAAB4C4, false);
		graphics.text(this.font, Component.translatable("gui.customdiscs.artist_label"), x + 114, y + 58, 0xFFAAB4C4, false);
		graphics.text(this.font, Component.translatable("gui.customdiscs.color_label"), x + 12, y + 92, 0xFFAAB4C4, false);

		// Color swatches
		for (int i = 0; i < PRESET_COLORS.length; i++) {
			int sx = x + SWATCH_START_X + i * (SWATCH_SIZE + SWATCH_GAP);
			int sy = y + SWATCH_Y;
			graphics.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, 0xFF000000 | PRESET_COLORS[i]);
			int border = (i == selectedColorIndex) ? 0xFFFFFFFF : 0xFF2B3242;
			graphics.outline(sx - 1, sy - 1, SWATCH_SIZE + 2, SWATCH_SIZE + 2, border);
		}

		// Progress bar (left of Burn button)
		int barX = x + 12;
		int barY = y + 122;
		int barW = 114;
		int barH = 12;
		graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF10131A);
		graphics.outline(barX, barY, barW, barH, 0xFF3A4458);
		if (uploading || uploadProgress > 0) {
			int fillW = (int) ((barW - 2) * (uploadProgress / 100.0));
			int barColor = uploadProgress >= 100 ? 0xFF3CE089 : 0xFF38D2F5;
			if (fillW > 0) {
				graphics.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, barColor);
			}
		}

		// Divider line above player inventory
		graphics.fill(x + 8, y + 150, x + UI_WIDTH - 8, y + 151, 0xFF2F3748);

		// Player inventory slot grid (3x9 + 1x9 hotbar)
		drawInventorySlots(graphics, x + DiscBurnerMenu.INV_START_X, y + DiscBurnerMenu.INV_START_Y);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, false);
		String fullStatus = this.statusMessage.getString();
		String clipped = this.font.plainSubstrByWidth(fullStatus, UI_WIDTH - 24);
		boolean truncated = clipped.length() < fullStatus.length();
		String shown = truncated ? (this.font.plainSubstrByWidth(fullStatus, UI_WIDTH - 32) + "...") : fullStatus;
		graphics.text(this.font, shown, 12, 139, this.statusColor, false);
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		int statusLeft = this.leftPos + 10;
		int statusTop = this.topPos + 136;
		if (mouseX >= statusLeft && mouseX <= statusLeft + UI_WIDTH - 20 && mouseY >= statusTop && mouseY <= statusTop + 13) {
			String fullStatus = this.statusMessage.getString();
			if (this.font.width(fullStatus) > UI_WIDTH - 24) {
				List<Component> wrapped = wrapText(fullStatus, 46);
				graphics.setComponentTooltipForNextFrame(this.font, wrapped, mouseX, mouseY);
			}
		}
	}

	private static List<Component> wrapText(String text, int maxCharsPerLine) {
		List<Component> lines = new ArrayList<>();
		String remaining = text.trim();
		while (remaining.length() > maxCharsPerLine) {
			int split = remaining.lastIndexOf(' ', maxCharsPerLine);
			if (split <= 10) {
				split = maxCharsPerLine;
			}
			lines.add(Component.literal(remaining.substring(0, split).trim()));
			remaining = remaining.substring(split).trim();
		}
		if (!remaining.isEmpty()) {
			lines.add(Component.literal(remaining));
		}
		return lines;
	}

	private static void drawInventorySlots(GuiGraphicsExtractor graphics, int startX, int startY) {
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				drawSlotBox(graphics, startX + col * 18 - 1, startY + row * 18 - 1);
			}
		}
		int hotbarY = startY + 58;
		for (int col = 0; col < 9; col++) {
			drawSlotBox(graphics, startX + col * 18 - 1, hotbarY - 1);
		}
	}

	private static void drawSlotBox(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x, y, x + 18, y + 18, 0xFF131720);
		graphics.outline(x, y, 18, 18, 0xFF323B4E);
	}

	private static String formatTicks(int ticks) {
		int sec = Math.max(0, (int) Math.round(ticks / 20.0));
		return String.format("%d:%02d", sec / 60, sec % 60);
	}
}
