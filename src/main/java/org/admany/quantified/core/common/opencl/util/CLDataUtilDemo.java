package org.admany.quantified.core.common.opencl.util;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class CLDataUtilDemo {

    public static void demonstrateAdvancedDataLayout() {
        CLDataUtil.MemoryLayout layout = new CLDataUtil.MemoryLayout();

        Map<String, Object> kernelData = new HashMap<>();
        kernelData.put("vertexBuffer", new byte[1024]);
        kernelData.put("indexBuffer", 512);
        kernelData.put("textureCoords", new byte[256]);
        kernelData.put("computeGrid", 4096);

        ByteBuffer structuredBuffer = CLDataUtil.createKernelBuffer(null, kernelData);

        layout.forEachAllocation((key, alloc) -> {
            System.out.printf("Allocation: %s -> offset=%d, size=%d, type=%s%n",
                key, alloc.offset(), alloc.size(), alloc.type().code());
        });

        CLDataUtil.writeInt(structuredBuffer, "computeGrid", 1024, layout);
        CLDataUtil.writeFloat(structuredBuffer, "scaleFactor", 1.5f, layout);

        int gridSize = (int) CLDataUtil.readLong(structuredBuffer, "computeGrid", layout);
        System.out.println("Grid size read back: " + gridSize);
    }

    public static void demonstrateComputeBuffer() {
        ByteBuffer computeBuffer = CLDataUtil.createComputeBuffer(null, 8192, 0);

        CLDataUtil.MemoryLayout layout = new CLDataUtil.MemoryLayout(128);
        layout.allocate("resultData", CLDataUtil.DataType.RESULT_BUFFER, 8192);

        byte[] testData = new byte[1024];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) i;
        }

        CLDataUtil.transferToBuffer(computeBuffer, layout.getAllocation("resultData").offset(), testData);

        byte[] readBack = new byte[1024];
        CLDataUtil.transferFromBuffer(computeBuffer, layout.getAllocation("resultData").offset(), readBack);

        System.out.println("Data integrity check: " + (readBack[0] == 0 && readBack[1023] == -1));
    }
}