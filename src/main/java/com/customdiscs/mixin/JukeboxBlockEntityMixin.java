package com.customdiscs.mixin;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.registry.ModComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin extends BlockEntity {
	protected JukeboxBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Shadow
	public abstract ItemStack getTheItem();

	@Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
	private void customdiscs$allowCustomDiscPlacement(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (stack.has(ModComponents.CUSTOM_DISC_DATA) && this.getTheItem().isEmpty()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "setTheItem", at = @At("TAIL"))
	private void customdiscs$onSetTheItem(ItemStack stack, CallbackInfo ci) {
		if (!(this.level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (stack.has(ModComponents.CUSTOM_DISC_DATA)) {
			BlockState state = this.getBlockState();
			if (state.hasProperty(JukeboxBlock.HAS_RECORD) && !state.getValue(JukeboxBlock.HAS_RECORD)) {
				serverLevel.setBlock(this.worldPosition, state.setValue(JukeboxBlock.HAS_RECORD, true), 3);
			}
			CustomDiscsMod.JUKEBOX_MANAGER.onDiscInserted(serverLevel, this.worldPosition, stack);
		} else {
			CustomDiscsMod.JUKEBOX_MANAGER.onDiscRemoved(serverLevel, this.worldPosition);
		}
	}

	@Inject(method = "popOutTheItem", at = @At("HEAD"))
	private void customdiscs$onPopOutItem(CallbackInfo ci) {
		if (this.level instanceof ServerLevel serverLevel) {
			CustomDiscsMod.JUKEBOX_MANAGER.onDiscRemoved(serverLevel, this.worldPosition);
		}
	}

	@Inject(method = "setRemoved", at = @At("HEAD"))
	private void customdiscs$onRemoved(CallbackInfo ci) {
		if (this.level instanceof ServerLevel serverLevel) {
			CustomDiscsMod.JUKEBOX_MANAGER.onDiscRemoved(serverLevel, this.worldPosition);
		}
	}

	@Inject(method = "getComparatorOutput", at = @At("HEAD"), cancellable = true)
	private void customdiscs$comparatorOutput(CallbackInfoReturnable<Integer> cir) {
		if (this.getTheItem().has(ModComponents.CUSTOM_DISC_DATA)) {
			cir.setReturnValue(15);
		}
	}
}
