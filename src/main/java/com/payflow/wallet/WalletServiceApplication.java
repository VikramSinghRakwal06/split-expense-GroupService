package com.payflow.wallet;

import com.payflow.wallet.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * PayFlow wallet-service: owns wallet balances and the immutable money ledger.
 *
 * <p>Validates JWTs issued by auth-service; it never mints one and holds no user table.
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class WalletServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(WalletServiceApplication.class, args);
	}

}
