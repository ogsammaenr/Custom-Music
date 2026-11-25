package com.customdiscs.item;

import com.customdiscs.component.CustomDiscData;
import com.customdiscs.registry.ModComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.function.Consumer;

public class CustomMusicDiscItem extends Item {
	public CustomMusicDiscItem(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		CustomDiscData data = stack.get(ModComponents.CUSTOM_DISC_DATA);
		if (data != null && !data.title().isBlank()) {
			return Component.literal(data.formattedDisplayName());
		}
		return super.getName(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltipAdder, TooltipFlag flag) {
		CustomDiscData data = stack.get(ModComponents.CUSTOM_DISC_DATA);
		if (data == null) {
			tooltipAdder.accept(Component.translatable("tooltip.customdiscs.empty_disc").withStyle(ChatFormatting.GRAY));
			return;
		}
		tooltipAdder.accept(Component.literal(data.formattedDisplayName()).withStyle(ChatFormatting.AQUA));
		tooltipAdder.accept(Component.translatable("tooltip.customdiscs.duration", data.formattedDuration()).withStyle(ChatFormatting.GRAY));
		if (flag.isAdvanced() && data.trackId().length() >= 12) {
			tooltipAdder.accept(Component.translatable("tooltip.customdiscs.track_id", data.trackId().substring(0, 12)).withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		ItemStack stack = context.getItemInHand();

		if (!state.is(Blocks.JUKEBOX) || state.getValue(JukeboxBlock.HAS_RECORD)) {
			return InteractionResult.PASS;
		}

		CustomDiscData data = stack.get(ModComponents.CUSTOM_DISC_DATA);
		if (data == null) {
			return InteractionResult.PASS;
		}

		if (!level.isClientSide()) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof JukeboxBlockEntity jukebox && jukebox.getTheItem().isEmpty()) {
				Player player = context.getPlayer();
				ItemStack singleDisc = (player != null && player.hasInfiniteMaterials())
						? stack.copyWithCount(1)
						: stack.split(1);
				jukebox.setTheItem(singleDisc);
				level.setBlock(pos, state.setValue(JukeboxBlock.HAS_RECORD, true), 3);
				level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
			}
		}

		return InteractionResult.SUCCESS;
	}
}
