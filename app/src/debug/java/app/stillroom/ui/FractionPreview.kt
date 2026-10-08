package app.stillroom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.stillroom.fractions.QuantityFractions
import java.math.BigDecimal

/** Design-time preview only; no route or production feature screen. */
@Preview(showBackground = true)
@Composable
fun FractionPreview() {
    val fractions = QuantityFractions()
    MaterialTheme {
        Column(Modifier.padding(16.dp)) {
            Text("Quantity display examples")
            for (stored in listOf("0", "0.5", "1.25", "0.3333333333", "0.123456")) {
                Text("$stored → ${fractions.format(BigDecimal(stored)).text}")
            }
        }
    }
}
