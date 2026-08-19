package com.splitexpense.group;

import com.splitexpense.group.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * SplitExpense group-service: owns groups, membership and the pairwise debt graph.
 *
 * <p>Holds no money and no wallets. What it holds is a signed balance for every pair of
 * people in a group, and the append-only entries that explain how each one got there.
 *
 * <p>Validates JWTs issued by auth-service; it never mints one and holds no user table.
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class GroupServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GroupServiceApplication.class, args);
	}

}
