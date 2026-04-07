# 🌱 KrishiSetu  
### AI-Powered Soil Intelligence for Smart & Sustainable Farming  

> Transforming soil data into actionable farming decisions through AI, IoT, and real-time analytics.

---

## 📌 Overview

KrishiSetu is a low-cost, AI-driven precision agriculture platform designed to monitor, analyze, and improve soil health in real time. It replaces traditional static soil testing with a continuous soil intelligence system by integrating IoT-based sensing, image analysis, weather data, and machine learning.

The platform predicts soil nutrient levels (NPK) and provides field-specific recommendations for fertilizer usage, crop selection, and soil restoration — enabling farmers to make data-driven, cost-effective, and sustainable decisions.

---

## 🚀 Key Features

- 🌡 **Real-Time Soil Monitoring**
  - Soil moisture and temperature using IoT sensors (ESP32)

- 📸 **Image-Based Soil Analysis**
  - Soil type detection via color analysis  
  - pH strip validation using mobile camera  

- 🤖 **AI-Based NPK Prediction**
  - Predicts Nitrogen (N), Phosphorus (P), Potassium (K)  
  - Uses multi-source data fusion (sensor + image + weather)

- 🌦 **Weather-Based Fertilizer Recommendation**
  - Adjusts fertilizer dosage based on rainfall, temperature, and humidity  
  - Prevents nutrient loss and improves efficiency  

- 🌾 **Crop Recommendation System**
  - Suggests suitable crops based on soil conditions  

- ♻️ **Soil Restoration Advisory**
  - Organic inputs (compost, biofertilizers)  
  - Sustainable farming practices  

- 📱 **Mobile Application**
  - Real-time dashboard  
  - Farmer-friendly interface  


## 🏗️ System Architecture
- IoT Sensors + Image Input
  ↓
- Mobile App
  ↓
- Firebase Cloud
  ↓
- Data Processing Layer
  ↓
- AI Model (NPK Prediction)
  ↓
- Decision Engine
  ↓
- Farmer Dashboard (Recommendations)

---

## ⚙️ Tech Stack

### 🧠 AI / ML
- Python  
- Random Forest  
- TensorFlow Lite (TFLite)

### 📡 IoT
- ESP32  
- Soil Moisture Sensor  
- Temperature Sensor  

### 📱 Mobile App
- Android (Kotlin)

### ☁️ Backend
- Firebase Realtime Database  
- REST APIs  

### 🌦 External APIs
- OpenWeatherMap Weather API (Temperature, Humidity, Rainfall)

---

## 🔬 Methodology

1. **Data Acquisition**
   - Soil parameters via IoT sensors  
   - Image input for soil and pH analysis  

2. **Feature Extraction**
   - Combined feature vector:
     ```
     X = [Moisture, Temperature, Humidity, SoilType, SoilColor, pH]
     ```

3. **Data Normalization**
   - X_norm = (X - μ) / σ
  

4. **NPK Prediction**
- Machine Learning model predicts:
  - Nitrogen (N)
  - Phosphorus (P)
  - Potassium (K)

5. **Decision Support**
- Fertilizer recommendations  
- Crop advisory  
- Soil restoration suggestions  

---

## 🎯 Problem Statement

Soil degradation is reducing agricultural productivity due to excessive chemical fertilizer use, poor nutrient management, and lack of real-time monitoring. Traditional soil testing is costly, time-consuming, and inaccessible to many farmers, leading to incorrect fertilizer usage and declining soil health.

KrishiSetu addresses this by providing a low-cost, real-time, and data-driven solution for precise soil analysis and sustainable farming.

---

## 📦 Deliverables

- IoT-based soil monitoring system  
- AI-powered NPK prediction model  
- Decision support system for farmers  
- Mobile application with real-time dashboard  
- Cloud-based data storage (Firebase)  

---

## 📈 Impact

- Reduces excessive fertilizer usage  
- Lowers farming input costs from Rs. 1500-5000 to ~Rs. 800 (One-time Purchase) 
- Improves soil health and productivity by 60%
- Enables data-driven farming decisions  
- Supports sustainable agriculture practices  

---

## 🔮 Future Scope

- Satellite-based NDVI integration  
- Government scheme integration  
- Multi-region deployment  
- Advanced soil carbon tracking  
- Predictive crop yield analytics  

---
