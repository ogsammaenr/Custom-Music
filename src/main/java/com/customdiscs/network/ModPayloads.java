package com.customdiscs.network;

import com.customdiscs.CustomDiscsMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class ModPayloads {
	public static final int CHUNK_SIZE = 24 * 1024; // 24 KB per network packet

	public static final byte UPLOAD_STATUS_ACCEPTED = 1;
	public static final byte UPLOAD_STATUS_PROGRESS = 2;
	public static final byte UPLOAD_STATUS_COMPLETE = 3;
	public static final byte UPLOAD_STATUS_ERROR = 4;

	private ModPayloads() {
	}

	public static void registerTypes() {
		// Client -> Server
		PayloadTypeRegistry.serverboundPlay().register(TrackUploadInitPayload.TYPE, TrackUploadInitPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TrackUploadChunkPayload.TYPE, TrackUploadChunkPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TrackDownloadRequestPayload.TYPE, TrackDownloadRequestPayload.STREAM_CODEC);

		// Server -> Client
		PayloadTypeRegistry.clientboundPlay().register(ServerConfigSyncPayload.TYPE, ServerConfigSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackUploadStatusPayload.TYPE, TrackUploadStatusPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackDownloadChunkPayload.TYPE, TrackDownloadChunkPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackDownloadErrorPayload.TYPE, TrackDownloadErrorPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlayCustomDiscPayload.TYPE, PlayCustomDiscPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StopCustomDiscPayload.TYPE, StopCustomDiscPayload.STREAM_CODEC);
	}

	public record TrackUploadInitPayload(
			String trackId,
			int totalSize,
			int totalChunks,
			String title,
			String artist,
			int durationTicks,
			int color,
			BlockPos burnerPos
	) implements CustomPacketPayload {
		public static final Type<TrackUploadInitPayload> TYPE = new Type<>(CustomDiscsMod.id("track_upload_init"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackUploadInitPayload> STREAM_CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.trackId, 64);
					buf.writeVarInt(p.totalSize);
					buf.writeVarInt(p.totalChunks);
					buf.writeUtf(p.title, 64);
					buf.writeUtf(p.artist, 64);
					buf.writeVarInt(p.durationTicks);
					buf.writeInt(p.color);
					buf.writeBlockPos(p.burnerPos);
				},
				buf -> new TrackUploadInitPayload(
						buf.readUtf(64),
						buf.readVarInt(),
						buf.readVarInt(),
						buf.readUtf(64),
						buf.readUtf(64),
						buf.readVarInt(),
						buf.readInt(),
						buf.readBlockPos()
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackUploadChunkPayload(
			String trackId,
			int chunkIndex,
			byte[] data
	) implements CustomPacketPayload {
		public static final Type<TrackUploadChunkPayload> TYPE = new Type<>(CustomDiscsMod.id("track_upload_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackUploadChunkPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), TrackUploadChunkPayload::trackId,
				ByteBufCodecs.VAR_INT, TrackUploadChunkPayload::chunkIndex,
				ByteBufCodecs.byteArray(CHUNK_SIZE), TrackUploadChunkPayload::data,
				TrackUploadChunkPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackDownloadRequestPayload(String trackId) implements CustomPacketPayload {
		public static final Type<TrackDownloadRequestPayload> TYPE = new Type<>(CustomDiscsMod.id("track_download_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackDownloadRequestPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), TrackDownloadRequestPayload::trackId,
				TrackDownloadRequestPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ServerConfigSyncPayload(
			int maxFileSizeBytes,
			int maxDurationSeconds,
			int opusBitrateBps
	) implements CustomPacketPayload {
		public static final Type<ServerConfigSyncPayload> TYPE = new Type<>(CustomDiscsMod.id("server_config_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSyncPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ServerConfigSyncPayload::maxFileSizeBytes,
				ByteBufCodecs.VAR_INT, ServerConfigSyncPayload::maxDurationSeconds,
				ByteBufCodecs.VAR_INT, ServerConfigSyncPayload::opusBitrateBps,
				ServerConfigSyncPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackUploadStatusPayload(
			String trackId,
			byte status,
			int progressPercent,
			String message
	) implements CustomPacketPayload {
		public static final Type<TrackUploadStatusPayload> TYPE = new Type<>(CustomDiscsMod.id("track_upload_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackUploadStatusPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), TrackUploadStatusPayload::trackId,
				ByteBufCodecs.BYTE, TrackUploadStatusPayload::status,
				ByteBufCodecs.VAR_INT, TrackUploadStatusPayload::progressPercent,
				ByteBufCodecs.stringUtf8(256), TrackUploadStatusPayload::message,
				TrackUploadStatusPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackDownloadChunkPayload(
			String trackId,
			int chunkIndex,
			int totalChunks,
			byte[] data
	) implements CustomPacketPayload {
		public static final Type<TrackDownloadChunkPayload> TYPE = new Type<>(CustomDiscsMod.id("track_download_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackDownloadChunkPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), TrackDownloadChunkPayload::trackId,
				ByteBufCodecs.VAR_INT, TrackDownloadChunkPayload::chunkIndex,
				ByteBufCodecs.VAR_INT, TrackDownloadChunkPayload::totalChunks,
				ByteBufCodecs.byteArray(CHUNK_SIZE), TrackDownloadChunkPayload::data,
				TrackDownloadChunkPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackDownloadErrorPayload(
			String trackId,
			String reason
	) implements CustomPacketPayload {
		public static final Type<TrackDownloadErrorPayload> TYPE = new Type<>(CustomDiscsMod.id("track_download_error"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackDownloadErrorPayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), TrackDownloadErrorPayload::trackId,
				ByteBufCodecs.stringUtf8(256), TrackDownloadErrorPayload::reason,
				TrackDownloadErrorPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PlayCustomDiscPayload(
			BlockPos pos,
			String trackId,
			String title,
			String artist,
			int elapsedTicks
	) implements CustomPacketPayload {
		public static final Type<PlayCustomDiscPayload> TYPE = new Type<>(CustomDiscsMod.id("play_custom_disc"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlayCustomDiscPayload> STREAM_CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, PlayCustomDiscPayload::pos,
				ByteBufCodecs.stringUtf8(64), PlayCustomDiscPayload::trackId,
				ByteBufCodecs.stringUtf8(64), PlayCustomDiscPayload::title,
				ByteBufCodecs.stringUtf8(64), PlayCustomDiscPayload::artist,
				ByteBufCodecs.VAR_INT, PlayCustomDiscPayload::elapsedTicks,
				PlayCustomDiscPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record StopCustomDiscPayload(BlockPos pos) implements CustomPacketPayload {
		public static final Type<StopCustomDiscPayload> TYPE = new Type<>(CustomDiscsMod.id("stop_custom_disc"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StopCustomDiscPayload> STREAM_CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, StopCustomDiscPayload::pos,
				StopCustomDiscPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
