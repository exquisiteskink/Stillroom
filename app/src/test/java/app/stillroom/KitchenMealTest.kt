package app.stillroom

import app.stillroom.domain.KitchenMeal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class KitchenMealTest {
    @Test fun breakfastCoversMorning() {
        assertEquals(KitchenMeal.Breakfast, KitchenMeal.at(LocalTime.of(5, 0)))
        assertEquals(KitchenMeal.Breakfast, KitchenMeal.at(LocalTime.of(8, 30)))
        assertEquals(KitchenMeal.Breakfast, KitchenMeal.at(LocalTime.of(10, 59)))
    }

    @Test fun lunchCoversMidday() {
        assertEquals(KitchenMeal.Lunch, KitchenMeal.at(LocalTime.of(11, 0)))
        assertEquals(KitchenMeal.Lunch, KitchenMeal.at(LocalTime.of(12, 15)))
        assertEquals(KitchenMeal.Lunch, KitchenMeal.at(LocalTime.of(15, 59)))
    }

    @Test fun dinnerCoversEveningAndNight() {
        assertEquals(KitchenMeal.Dinner, KitchenMeal.at(LocalTime.of(16, 0)))
        assertEquals(KitchenMeal.Dinner, KitchenMeal.at(LocalTime.of(19, 45)))
        assertEquals(KitchenMeal.Dinner, KitchenMeal.at(LocalTime.of(23, 59)))
        assertEquals(KitchenMeal.Dinner, KitchenMeal.at(LocalTime.of(0, 0)))
        assertEquals(KitchenMeal.Dinner, KitchenMeal.at(LocalTime.of(4, 59)))
    }
}
