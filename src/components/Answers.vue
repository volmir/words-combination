<template>
    <div>

                        
<h4>Составление слов из букв и других слов</h4>

<div v-if="error" class="alert alert-danger alert-dismissible fade show" role="alert">
  Произошла ошибка при загрузке данных
  <button type="button" class="close" v-on:click="closeError" aria-label="Закрыть">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div v-if="warning" class="alert alert-warning alert-dismissible fade show" role="alert">
  Не найдено комбинаций для слова "{{previousWord}}"
  <button type="button" class="close" v-on:click="closeWarning" aria-label="Закрыть">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово или последовательность букв, из которых нужно составить слова</span>
    </p>
    <form class="form-inline" method="get">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" v-model="word" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" v-on:click.stop.prevent="onSubmit">Искать варианты</button>
    </form>
</div>

<template v-if="words && words.length">
    <p>
        <i>Из "{{previousWord}}" можно составить {{wordsStat.total}} слов из {{wordsStat.counts}} букв.</i>
    </p>
    <p v-for="(counts, idx) of words" v-bind:key="idx">
        Слова из {{counts[0].vocab.length}} букв, составленные из комбинации "портал" ({{counts.length}} слов):
        <template v-if="counts">
            <span v-for="(word, idy) of counts" v-bind:key="idy">
                <router-link :to="{name: 'description', params: {initialWord: word.vocab}}">{{word.vocab}}</router-link>
                <template v-if="idy < (counts.length - 1)">, </template> 
            </span> 
        </template>         
    </p>        
</template> 


    <Waiting ref="waitingModal"></Waiting>     

    </div>
</template>

<script>

import axios from 'axios';
import Waiting from './modal/Waiting.vue';

export default {
    components: {
        Waiting
    },
    data: function() {
        return {
            word: '',
            previousWord: '',            
            words: [],
            wordsStat: {total: 0, counts: ''},
            error: false,
            warning: false
        }
    },
    methods: {
        onSubmit: function () {
            if (this.word.length) { 
                this.$modal.show('waiting-modal');
                this.error = false;
                this.warning = false;

                axios.get('http://combination-words.local/words/' + encodeURI(this.word))
                .then(response => {    
                    document.title = 'Слова, которые можно составить из "' + this.word + '"';
                    this.previousWord = this.word;
                    this.words = Object.values(response.data.data);
                    this.error = false; 
                    this.wordsStat = {total: 0, counts: ''};
                        
                    if (!this.words.length) {
                        this.warning = true;
                    } else {
                        for (var variant in this.words) {                            
                            this.wordsStat.counts += this.words[variant][0].vocab.length + ', ';
                            this.wordsStat.total += this.words[variant].length;
                        }          
                        this.wordsStat.counts = this.wordsStat.counts.substring(0, this.wordsStat.counts.length - 2); 
                    }
                })
                .catch(e => {
                    this.error = e;
                }).then(() => {
                    this.$modal.hide('waiting-modal');
                });
            }
        },
        closeError: function () {   
            this.error = false;
        },
        closeWarning: function () {   
            this.warning = false;
        }
    }     
}
</script>

<style scoped>

</style>