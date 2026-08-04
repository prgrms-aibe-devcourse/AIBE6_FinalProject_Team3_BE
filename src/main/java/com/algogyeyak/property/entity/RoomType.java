package com.algogyeyak.property.entity;

/**
 * 매물 이미지가 어느 공간을 찍은 사진인지 나타내는 라벨.
 * 필수 아님 - 라벨 없이 이미지만 올리는 것도 허용한다(PropertyImage.roomType은 nullable).
 */
public enum RoomType {
    LIVING_ROOM,
    BEDROOM,
    BATHROOM,
    KITCHEN,
    ENTRANCE,
    VERANDA,
    EXTERIOR,
    ETC
}
