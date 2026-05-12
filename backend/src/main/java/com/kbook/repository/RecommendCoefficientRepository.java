package com.kbook.repository;

import com.kbook.entity.RecommendCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecommendCoefficientRepository extends JpaRepository<RecommendCoefficient, Long> {

    Optional<RecommendCoefficient> findByCategoryAndCoeffKey(String category, String coeffKey);

    List<RecommendCoefficient> findByCategory(String category);

    List<RecommendCoefficient> findByLockedFalse();
}
