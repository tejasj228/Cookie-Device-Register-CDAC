package com.example.cookiedemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The starting point of the whole backend.
 *
 * Running the main() method below boots up an HTTPS server on port 8443 and
 * starts the embedded H2 database. That's it.
 *
 * @EnableScheduling switches on the @Scheduled method in SessionService that
 * sweeps expired session rows out of the table once an hour.
 */
@SpringBootApplication
@EnableScheduling
public class CookieDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CookieDemoApplication.class, args);
    }
}
