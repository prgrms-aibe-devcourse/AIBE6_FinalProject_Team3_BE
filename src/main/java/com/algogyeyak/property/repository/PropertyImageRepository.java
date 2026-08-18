package com.algogyeyak.property.repository;

import com.algogyeyak.property.entity.PropertyImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, Long> {

    /**
     * 매물 목록 조회(PropertyService.getMyProperties())에서 대표 이미지를 배치로 가져오기 위한
     * 조회. sortOrder는 현재 업로드 플로우(PropertyService.applyImages())에서 채워지지 않아
     * 항상 null이라 정렬 기준으로 못 쓰고, id 오름차순(=업로드된 순서)을 대표 이미지 판단 기준으로
     * 쓴다. 매물별로 가장 먼저 업로드된 한 장만 필요하므로, 호출부에서 propertyId별로 묶은 뒤
     * 이 정렬 순서상 처음 나오는 행만 취한다.
     */
    List<PropertyImage> findByProperty_IdInOrderByProperty_IdAscIdAsc(List<Long> propertyIds);

    /**
     * PropertyImageOrphanCleanupJob이 "S3에는 있는데 DB엔 참조가 없는" 객체를 가려내기 위한 조회.
     * 엔티티 전체(특히 property 연관관계)를 로딩할 필요가 없어 imageUrl 컬럼만 뽑는 스칼라 쿼리로
     * 뒀다 - 매물 이미지 전체 건수만큼 엔티티/프록시를 만들지 않아도 된다.
     */
    @Query("select p.imageUrl from PropertyImage p")
    List<String> findAllImageUrls();
}
