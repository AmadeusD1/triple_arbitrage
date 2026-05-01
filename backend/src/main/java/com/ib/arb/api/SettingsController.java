package com.ib.arb.api;

import com.ib.arb.model.Setting;
import com.ib.arb.repository.SettingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingRepository settingRepo;

    public SettingsController(SettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    @GetMapping
    public ResponseEntity<List<Setting>> getAll() {
        return ResponseEntity.ok(settingRepo.findAll());
    }

    @PutMapping
    public ResponseEntity<Void> update(@RequestBody Map<String, Double> updates) {
        updates.forEach((key, value) -> {
            var setting = settingRepo.findById(key).orElse(new Setting());
            setting.setKey(key);
            setting.setValue(value);
            settingRepo.save(setting);
        });
        return ResponseEntity.ok().build();
    }
}
