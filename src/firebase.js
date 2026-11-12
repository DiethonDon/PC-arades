import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

const firebaseConfig = {
  apiKey: "YOUR_API_KEY",
  authDomain: "pc-arades.firebaseapp.com",
  projectId: "pc-arades",
  storageBucket: "pc-arades.firebasestorage.app",
  messagingSenderId: "956510806481",
  appId: "1:956510806481:web:a8746feb836dab1173c0f0"
};

const app = initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getFirestore(app);
