package example.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class PropertyNoteServiceTest {
  @Test void urgentWaterReportBecomesMaintenanceInsteadOfDocument() {
    assertEquals("maintenance", PropertyNoteService.category("Tenant reports water leaking by the boiler"));
    assertEquals("inspection-reminder", PropertyNoteService.category("Schedule annual inspection"));
    assertEquals("tenant-document", PropertyNoteService.category("Signed lease received"));
  }
}
