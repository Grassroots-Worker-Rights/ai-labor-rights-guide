package com.angeasla.ai_labor_rights_guide;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// DeepSeek's auto-config refuses to start without a key; no call is made, so any value works here.
@SpringBootTest(properties = "spring.ai.deepseek.api-key=test-dummy-key")
class AiLaborRightsGuideApplicationTests {

	@Test
	void contextLoads() {
	}

}
