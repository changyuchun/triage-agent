package com.cyc.cyctest.agent.core;

import org.apache.poi.ss.formula.functions.Forecast;

/**
 * @author daijian.cyc
 * @version 1.0
 * @since 2026/6/29 14:39
 */
public class R {
    public static void main(String[] args) {
        int[] coins =new int[]{1, 2, 5};
        int amount = 11;
        System.out.println(dp(coins, amount));
    }


    public static int dp(int[] coins, int amount) {
        int[] dp = new int[amount + 1];

        for (int i = 1; i < amount + 1; i++) {
            dp[i]=amount+1;
        }
        for (int coin : coins){
            for (int i = coin; i < amount + 1; i++) {
                dp[i] = Math.min(dp[i], dp[i - coin] + 1);
            }
        }
        return dp[amount] > amount ? -1 : dp[amount];
    }

}

