package com.sapsr.backend;

import com.sapsr.backend.academic.AcademicCalendar;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {
	public static void main(String[] args) {
		// Всё время в системе — по Минску, а не по часовому поясу машины: VPN для ngrok переводит её в пояс
		// другой страны. От пояса JVM зависят и время в базе: драйвер PostgreSQL передаёт его сессии,
		// а created_at заполняется в ней по умолчанию.
		TimeZone.setDefault(TimeZone.getTimeZone(AcademicCalendar.ZONE));
		SpringApplication.run(BackendApplication.class, args);
	}
}
