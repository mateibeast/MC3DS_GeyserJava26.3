package dev.mc3ds.bridge;

/**
 * Java 1.12 block breaking time (Block#getPlayerRelativeBlockHardness + EntityPlayer#getDigSpeed),
 * so the bridge can tell the server a block is broken exactly when a Java client would.
 */
final class BreakTime {
    static final float[] HARDNESS = new float[256];
    /** 'P' pickaxe, 'A' axe, 'S' shovel, 'N' none */
    static final char[] TOOL = new char[256];
    /** -1 = no tool needed, 0..3 = pickaxe tier needed, 10 = shovel needed, 20 = sword/shears needed */
    static final int[] NEEDS = new int[256];

    private static void def(int id, float h, char tool, int needs) { HARDNESS[id] = h; TOOL[id] = tool; NEEDS[id] = needs; }
    private static void range(int from, int to, float h, char tool, int needs) { for (int i = from; i <= to; i++) def(i, h, tool, needs); }

    static {
        for (int i = 0; i < 256; i++) def(i, 1f, 'N', -1);
        def(0, 0, 'N', -1);
        def(1, 1.5f, 'P', 0); def(2, 0.6f, 'S', -1); def(3, 0.5f, 'S', -1); def(4, 2, 'P', 0); def(5, 2, 'A', -1);
        def(6, 0, 'N', -1); def(7, -1, 'N', -1); range(8, 11, 100, 'N', -1); def(12, 0.5f, 'S', -1); def(13, 0.6f, 'S', -1);
        def(14, 3, 'P', 2); def(15, 3, 'P', 1); def(16, 3, 'P', 0); def(17, 2, 'A', -1); def(18, 0.2f, 'N', -1);
        def(19, 0.6f, 'N', -1); def(20, 0.3f, 'N', -1); def(21, 3, 'P', 1); def(22, 3, 'P', 1); def(23, 3.5f, 'P', 0);
        def(24, 0.8f, 'P', 0); def(25, 0.8f, 'A', -1); def(26, 0.2f, 'N', -1); def(27, 0.7f, 'P', -1); def(28, 0.7f, 'P', -1);
        def(29, 0.5f, 'N', -1); def(30, 4, 'N', 20); def(31, 0, 'N', -1); def(32, 0, 'N', -1); def(33, 0.5f, 'N', -1);
        def(34, 0.5f, 'N', -1); def(35, 0.8f, 'N', -1); def(36, -1, 'N', -1); range(37, 40, 0, 'N', -1);
        def(41, 3, 'P', 2); def(42, 5, 'P', 1); def(43, 2, 'P', 0); def(44, 2, 'P', 0); def(45, 2, 'P', 0); def(46, 0, 'N', -1);
        def(47, 1.5f, 'A', -1); def(48, 2, 'P', 0); def(49, 50, 'P', 3); def(50, 0, 'N', -1); def(51, 0, 'N', -1);
        def(52, 5, 'P', 0); def(53, 2, 'A', -1); def(54, 2.5f, 'A', -1); def(55, 0, 'N', -1); def(56, 3, 'P', 2);
        def(57, 5, 'P', 2); def(58, 2.5f, 'A', -1); def(59, 0, 'N', -1); def(60, 0.6f, 'S', -1); def(61, 3.5f, 'P', 0);
        def(62, 3.5f, 'P', 0); def(63, 1, 'A', -1); def(64, 3, 'A', -1); def(65, 0.4f, 'A', -1); def(66, 0.7f, 'P', -1);
        def(67, 2, 'P', 0); def(68, 1, 'A', -1); def(69, 0.5f, 'N', -1); def(70, 0.5f, 'P', 0); def(71, 5, 'P', 0);
        def(72, 0.5f, 'A', -1); def(73, 3, 'P', 2); def(74, 3, 'P', 2); def(75, 0, 'N', -1); def(76, 0, 'N', -1);
        def(77, 0.5f, 'P', -1); def(78, 0.1f, 'S', 10); def(79, 0.5f, 'P', -1); def(80, 0.2f, 'S', 10); def(81, 0.4f, 'N', -1);
        def(82, 0.6f, 'S', -1); def(83, 0, 'N', -1); def(84, 2, 'A', -1); def(85, 2, 'A', -1); def(86, 1, 'A', -1);
        def(87, 0.4f, 'P', 0); def(88, 0.5f, 'S', -1); def(89, 0.3f, 'N', -1); def(90, -1, 'N', -1); def(91, 1, 'A', -1);
        def(92, 0.5f, 'N', -1); def(93, 0, 'N', -1); def(94, 0, 'N', -1); def(95, 0.3f, 'N', -1); def(96, 3, 'A', -1);
        def(97, 0.75f, 'N', -1); def(98, 1.5f, 'P', 0); def(99, 0.2f, 'A', -1); def(100, 0.2f, 'A', -1); def(101, 5, 'P', 0);
        def(102, 0.3f, 'N', -1); def(103, 1, 'A', -1); def(104, 0, 'N', -1); def(105, 0, 'N', -1); def(106, 0.2f, 'A', -1);
        def(107, 2, 'A', -1); def(108, 2, 'P', 0); def(109, 1.5f, 'P', 0); def(110, 0.6f, 'S', -1); def(111, 0, 'N', -1);
        def(112, 2, 'P', 0); def(113, 2, 'P', 0); def(114, 2, 'P', 0); def(115, 0, 'N', -1); def(116, 5, 'P', 0);
        def(117, 0.5f, 'P', 0); def(118, 2, 'P', 0); def(119, -1, 'N', -1); def(120, -1, 'N', -1); def(121, 3, 'P', 0);
        def(122, 3, 'N', -1); def(123, 0.3f, 'N', -1); def(124, 0.3f, 'N', -1); def(125, 2, 'A', -1); def(126, 2, 'A', -1);
        def(127, 0.2f, 'A', -1); def(128, 0.8f, 'P', 0); def(129, 3, 'P', 2); def(130, 22.5f, 'P', 0); def(131, 0, 'N', -1);
        def(132, 0, 'N', -1); def(133, 5, 'P', 2); range(134, 136, 2, 'A', -1); def(137, -1, 'N', -1); def(138, 3, 'N', -1);
        def(139, 2, 'P', 0); def(140, 0, 'N', -1); def(141, 0, 'N', -1); def(142, 0, 'N', -1); def(143, 0.5f, 'A', -1);
        def(144, 1, 'N', -1); def(145, 5, 'P', 0); def(146, 2.5f, 'A', -1); def(147, 0.5f, 'P', 0); def(148, 0.5f, 'P', 0);
        def(149, 0, 'N', -1); def(150, 0, 'N', -1); def(151, 0.2f, 'A', -1); def(152, 5, 'P', 0); def(153, 3, 'P', 0);
        def(154, 3, 'P', 0); def(155, 0.8f, 'P', 0); def(156, 0.8f, 'P', 0); def(157, 0.7f, 'P', -1); def(158, 3.5f, 'P', 0);
        def(159, 1.25f, 'P', 0); def(160, 0.3f, 'N', -1); def(161, 0.2f, 'N', -1); def(162, 2, 'A', -1); def(163, 2, 'A', -1);
        def(164, 2, 'A', -1); def(165, 0, 'N', -1); def(166, -1, 'N', -1); def(167, 5, 'P', 0); def(168, 1.5f, 'P', 0);
        def(169, 0.3f, 'N', -1); def(170, 0.5f, 'N', -1); def(171, 0.1f, 'N', -1); def(172, 1.25f, 'P', 0); def(173, 5, 'P', 0);
        def(174, 0.5f, 'P', -1); def(175, 0, 'N', -1); def(176, 1, 'A', -1); def(177, 1, 'A', -1); def(178, 0.2f, 'A', -1);
        def(179, 0.8f, 'P', 0); def(180, 0.8f, 'P', 0); def(181, 2, 'P', 0); def(182, 2, 'P', 0); range(183, 192, 2, 'A', -1);
        range(193, 197, 3, 'A', -1); def(198, 0, 'N', -1); def(199, 0.4f, 'A', -1); def(200, 0.4f, 'A', -1); range(201, 205, 1.5f, 'P', 0);
        def(204, 2, 'P', 0); def(205, 2, 'P', 0); def(206, 0.8f, 'P', 0); def(207, 0, 'N', -1); def(208, 0.65f, 'S', -1);
        def(209, -1, 'N', -1); def(210, -1, 'N', -1); def(211, -1, 'N', -1); def(212, 0.5f, 'P', -1); def(213, 0.5f, 'P', 0);
        def(214, 1, 'N', -1); def(215, 2, 'P', 0); def(216, 2, 'P', 0); def(217, 0, 'N', -1); def(218, 3, 'P', 0);
        range(219, 234, 2, 'P', -1); range(235, 250, 1.4f, 'P', 0); def(251, 1.8f, 'P', 0); def(252, 0.5f, 'S', -1);
        def(255, -1, 'N', -1);
    }

    /** {tool kind 'P','A','S','W'(sword),'H'(shears) or 'N', tier speed, harvest level} for a Java item id */
    static float[] tool(int item) {
        switch (item) {
            case 270: return new float[]{'P', 2, 0}; case 274: return new float[]{'P', 4, 1}; case 257: return new float[]{'P', 6, 2};
            case 278: return new float[]{'P', 8, 3}; case 285: return new float[]{'P', 12, 0};
            case 271: return new float[]{'A', 2, 0}; case 275: return new float[]{'A', 4, 1}; case 258: return new float[]{'A', 6, 2};
            case 279: return new float[]{'A', 8, 3}; case 286: return new float[]{'A', 12, 0};
            case 269: return new float[]{'S', 2, 0}; case 273: return new float[]{'S', 4, 1}; case 256: return new float[]{'S', 6, 2};
            case 277: return new float[]{'S', 8, 3}; case 284: return new float[]{'S', 12, 0};
            case 268: case 272: case 267: case 276: case 283: return new float[]{'W', 1, 0};
            case 359: return new float[]{'H', 1, 0};
            default: return new float[]{'N', 1, 0};
        }
    }

    /**
     * @return break time in milliseconds (0 = instant, -1 = unbreakable)
     */
    static long millis(int javaState, int[] held, int haste, int fatigue, boolean inWaterNoAqua, boolean onGround) {
        int id = (javaState >> 4) & 0xFF;
        float hardness = HARDNESS[id];
        if (hardness < 0) return -1;
        if (hardness == 0) return 0;
        int item = held == null ? 0 : held[0];
        int efficiency = held != null && held.length > 3 ? held[3] : 0;
        float[] t = tool(item);
        char kind = (char) t[0];
        float speed = 1f;
        if (kind == 'H') {
            if (id == 30 || id == 18 || id == 161) speed = 15; else if (id == 35) speed = 5;
        } else if (kind == 'W') {
            if (id == 30) speed = 15;
            else if (id == 18 || id == 161 || id == 106 || id == 86 || id == 103 || id == 31 || id == 175 || id == 127) speed = 1.5f;
        } else if (kind != 'N' && kind == TOOL[id]) {
            speed = t[1];
        }
        if (speed > 1 && efficiency > 0) speed += efficiency * efficiency + 1;
        if (haste >= 0) speed *= 1 + (haste + 1) * 0.2f;
        if (fatigue >= 0) speed *= fatigue == 0 ? 0.3f : fatigue == 1 ? 0.09f : fatigue == 2 ? 0.0027f : 8.1E-4f;
        if (inWaterNoAqua) speed /= 5;
        if (!onGround) speed /= 5;
        int needs = NEEDS[id];
        boolean canHarvest;
        if (needs < 0) canHarvest = true;
        else if (needs <= 3) canHarvest = kind == 'P' && t[2] >= needs;
        else if (needs == 10) canHarvest = kind == 'S';
        else canHarvest = kind == 'W' || kind == 'H';
        float perTick = speed / hardness / (canHarvest ? 30f : 100f);
        if (perTick >= 1) return 0;
        long ticks = (long) Math.ceil(1.0 / perTick);
        return ticks * 50L;
    }
}
