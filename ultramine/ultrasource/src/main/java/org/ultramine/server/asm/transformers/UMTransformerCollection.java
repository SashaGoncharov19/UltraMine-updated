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
		registerGlobalTransformer(new FieldSetCompatTransformer());
		//Last of the global passes: it repairs what earlier ones may have
		//re-emitted, and it is the cheapest to skip - one bitmask test for
		//every class that is not an interface.
		registerGlobalTransformer(new InterfaceMethodrefRepairTransformer());
		registerSpecialTransformer(new BlockLeavesBaseFixer(), "net.minecraft.block.BlockLeavesBase");
	}
}
