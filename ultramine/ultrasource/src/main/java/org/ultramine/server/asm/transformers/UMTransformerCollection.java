package org.ultramine.server.asm.transformers;

import org.ultramine.server.asm.UMTBatchTransformer;

public class UMTransformerCollection extends UMTBatchTransformer
{
	public UMTransformerCollection()
	{
		registerGlobalTransformer(new PrintStackTraceTransformer());
		registerGlobalTransformer(new TrigMathTransformer());
		registerGlobalTransformer(new ServiceInjectionTransformer());
		registerGlobalTransformer(new Log4jBeta9CompatTransformer());
		registerSpecialTransformer(new BlockLeavesBaseFixer(), "net.minecraft.block.BlockLeavesBase");
	}
}
